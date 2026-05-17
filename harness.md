# Hermes Agent Core Harness

Architecture reference for re-implementing the Hermes Agent loop in any language.
Based on `run_agent.py`, `agent/conversation_loop.py`, `model_tools.py`, `tools/registry.py`, `toolsets.py`, `cli.py`, and `agent/tool_guardrails.py`.

---

## 1. High-Level Architecture

```
┌─────────────────────────────────────────────────────────┐
│                      CLI (cli.py)                        │
│  prompt_toolkit input → HermesCLI.process_command()     │
│              ↓                                           │
│  session_search → memory recall → skill loading          │
│              ↓                                           │
│         AIAgent.run_conversation(msg)                    │
└──────────────────────────────────────────────────────────┘
                        │
                        ▼
┌──────────────────────────────────────────────────────────┐
│            run_conversation() — Core Loop                │
│                                                          │
│   1. Build api_messages (system + history + user msg)    │
│   2. Inject memory context, plugin hooks, steers         │
│   3. Call provider API with tool schemas                 │
│   4. Parse response → tool_calls or final text           │
│   5. Validate + guardrail → execute tools                │
│   6. Append tool results → loop back to 3                │
│   7. MAX_ITERATIONS (default 90) or budget exhausted     │
│                                                          │
│   Returns: {final_response, messages, api_calls, ...}    │
└──────────────────────────────────────────────────────────┘
                        │
                        ▼
┌──────────────────────────────────────────────────────────┐
│       Tool System (model_tools.py + tools/registry.py)   │
│                                                          │
│   registry.register(name, toolset, schema, handler_fn)   │
│   get_tool_definitions(enabled, disabled) → OpenAI schema │
│   handle_function_call(name, args) → JSON result string  │
│   registry.dispatch(name, args) → handler result         │
└──────────────────────────────────────────────────────────┘
```

## 2. The Agent Loop (Pseudocode)

This is the heart. It follows OpenAI chat completions format exactly.

```
def run_conversation(user_message, system_message=None, history=None):

    messages = history or []
    messages.append({"role": "user", "content": user_message})

    # System prompt — built ONCE per session, cached for prompt prefix caching
    system_prompt = build_system_prompt(system_message, memory, skills, tools)

    api_call_count = 0
    max_iterations = 90          # configurable
    finish_reason = "stop"

    while api_call_count < max_iterations:

        api_call_count += 1

        # Build API request payload
        api_messages = copy(messages)
        if system_prompt:
            api_messages = [{"role": "system", "content": system_prompt}] + api_messages

        # Inject ephemeral context (memory, plugin hooks) into user msg
        api_messages[current_user_idx] += injected_context

        # Call LLM
        response = POST /v1/chat/completions {
            "model": model_name,
            "messages": api_messages,
            "tools": tool_schemas,          # from get_tool_definitions()
            "tool_choice": "auto"
        }

        assistant_msg = response.choices[0].message

        # Handle finish_reason == "length" (truncated)
        if finish_reason == "length":
            retry with more tokens or mark as partial
            continue

        # Check for tool calls
        if assistant_msg.tool_calls:

            # Step 1: Validate tool names against valid_tool_names set
            for tc in assistant_msg.tool_calls:
                if tc.function.name not in valid_tool_names:
                    try name repair, else return error to model

            # Step 2: Validate JSON arguments
            for tc in assistant_msg.tool_calls:
                json.loads(tc.function.arguments)   # catch parse errors

            # Step 3: Deduplicate and cap parallel calls
            tool_calls = deduplicate(tool_calls)
            tool_calls = cap_delegate_task_calls(tool_calls)

            # Step 4: Build assistant message for history
            assistant_msg_for_history = {
                "role": "assistant",
                "content": assistant_msg.content or "",
                "tool_calls": [
                    {"id": tc.id, "type": "function",
                     "function": {"name": tc.function.name,
                                  "arguments": tc.function.arguments}}
                    for tc in tool_calls
                ]
            }
            messages.append(assistant_msg_for_history)

            # Step 5: Execute tools (sequential or concurrent)
            for tc in tool_calls:
                args = json.loads(tc.function.arguments)
                result = handle_function_call(tc.function.name, args)

                # Guardrail: appends warnings if same tool keeps failing
                result = append_guardrail_observation(tc.function.name, args, result)

                # Budget: truncate oversized results
                result = maybe_persist_tool_result(result)

                messages.append({
                    "role": "tool",
                    "name": tc.function.name,
                    "content": result,
                    "tool_call_id": tc.id
                })

            # Step 6: Context compression if approaching token limit
            if should_compress(messages):
                messages = compress_messages(messages)

            # Loop back for next API call
            continue

        else:
            # No tool calls — this is the final text response
            final_response = assistant_msg.content or ""
            break

    return {"final_response": final_response, "messages": messages,
            "api_calls": api_call_count, "completed": finish_reason == "stop"}
```

## 3. Tool System

### 3.1 Tool Registration (at import time)

Each tool is a self-contained file in `tools/` that calls `registry.register()` at module top-level:

```python
registry.register(
    name="web_search",                    # unique tool name
    toolset="web",                        # group for enable/disable
    schema={
        "description": "Search the web...",
        "parameters": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Search query"}
            },
            "required": ["query"]
        }
    },
    handler=web_search_handler,           # callable(args, **kwargs) → str
    check_fn=check_web_available,         # optional: returns bool
    requires_env=["SERPAPI_API_KEY"],     # optional: env var deps
    is_async=False,                       # if True, bridged via event loop
)
```

### 3.2 Tool Discovery

```python
def discover_builtin_tools(tools_dir):
    """Scan tools/*.py, AST-check for registry.register() calls, import each."""
    for py_file in sorted(tools_dir.glob("*.py")):
        if py_file.name in {"__init__.py", "registry.py", "mcp_tool.py"}:
            continue
        if has_top_level_register_call(py_file):
            importlib.import_module(f"tools.{py_file.stem}")
```

### 3.3 Schema Pipeline

```
1. discover_builtin_tools()   → imports all tool modules → registry populated
2. get_tool_definitions(enabled_toolsets, disabled_toolsets)
   - resolve_toolset(name)    → flattens toolset into list of tool names
   - registry.get_definitions(names)  → returns OpenAI-compatible schema dicts
     - filters via check_fn() (TTL-cached for 30s)
     - applies dynamic_schema_overrides
   - schema_sanitizer.strip_pattern_and_format() for backend compatibility
3. Schema format per tool:
   {"type": "function",
    "function": {
        "name": "...",
        "description": "...",
        "parameters": {"type": "object", "properties": {...}, "required": [...]}
    }}
```

### 3.4 Toolset System

Toolsets group tools and can compose:

```yaml
# toolsets.py defines:
TOOLSETS = {
    "web":       {"tools": ["web_search", "web_extract"],       "includes": []},
    "terminal":  {"tools": ["terminal", "process"],             "includes": []},
    "file":      {"tools": ["read_file", "write_file", "patch", "search_files"],
                   "includes": []},
    "development": {"tools": [], "includes": ["terminal", "file", "web"]},
}
```

### 3.5 Dispatch Pipeline

```
handle_function_call(name, args)
  │
  ├─ 1. coerce_tool_args(name, args)
  │      String→int, String→bool coercion based on JSON schema types
  │      Wraps bare scalars in arrays when schema declares "type": "array"
  │
  ├─ 2. Check plugin hooks for block directive
  │
  ├─ 3. registry.dispatch(name, args, **kwargs)
  │      ├─ Sync: handler(args, **kwargs) directly
  │      └─ Async: _run_async(handler(args, **kwargs)) via event loop
  │
  └─ 4. Post-tool: plugin hook + transform hook → return JSON string
```

## 4. Guardrails (agent/tool_guardrails.py)

The guardrail controller tracks per-turn tool-call patterns to detect loops.

### 4.1 Detection Patterns

| Pattern | Trigger | Action |
|---------|---------|--------|
| **Exact failure** | Same tool + same args failed N times | Warn at 2, block at 5 |
| **Same tool failure** | Tool failed N times (any args) | Warn at 3, halt at 8 |
| **Idempotent no-progress** | Read-only tool returned same result N times | Warn at 2, block at 5 |

### 4.2 Controller Lifecycle

```python
# Per-turn reset
controller.reset_for_turn()

# Before each tool call — checks hard-stop thresholds
decision = controller.before_call(tool_name, args)
if not decision.allows_execution:
    return synthetic_error(decision)

# After each tool call — updates counters, returns warn/halt
decision = controller.after_call(tool_name, args, result, failed=True/False)
if decision.action in ("warn", "halt"):
    append_toolguard_guidance(result, decision)  # appends warning text to result
```

### 4.3 Decision Actions

- **allow** — run normally
- **warn** — run but append warning text to tool result so the model sees it
- **block** — don't run, return synthetic error message
- **halt** — stop the entire tool-calling turn, produce controlled response

### 4.4 Idempotent vs Mutating Tools

- **Idempotent** (read-only): `read_file`, `search_files`, `web_search`, `web_extract`, `session_search`, `browser_snapshot`, `browser_console`, `browser_get_images`
- **Mutating**: `terminal`, `execute_code`, `write_file`, `patch`, `todo`, `memory`, `skill_manage`, `browser_click/type/press/scroll/navigate`, `send_message`, `cronjob`, `delegate_task`, `process`

## 5. Message Protocol

### 5.1 Supported Roles

Exactly OpenAI Chat Completions format:

```json
{"role": "system",    "content": "string"}
{"role": "user",      "content": "string | [{"type":"text","text":"..."}, {"type":"image_url","image_url":{"url":"data:..."}}]"}
{"role": "assistant", "content": "string", "tool_calls": [...]}
{"role": "tool",      "content": "string", "tool_call_id": "call_xxx", "name": "tool_name"}
```

### 5.2 Message Ordering Invariant

Must alternate: `user → assistant → tool → assistant → tool → ... → assistant` (no two user messages in a row, no tool message without a preceding assistant with matching tool_calls).

### 5.3 Tool Call ID Format

OpenAI-style string IDs (e.g. `"call_abc123"`). Each tool call gets a unique ID; tool results must reference the matching ID.

### 5.4 Tool Result Format

All tool results are JSON strings returned to the model:

```json
// Success
{"output": "results here", "exit_code": 0, ...}

// Error
{"error": "Tool execution failed: ErrorType: message"}

// Guardrail block (synthetic)
{"error": "Blocked tool_name: reason...", "guardrail": {"action": "block", ...}}
```

## 6. CLI Chat Handling (terminal mode)

### 6.1 Startup Flow

```
1. Read ~/.hermes/config.yaml
2. Load .env for API keys
3. discover_builtin_tools()  → import all tool modules
4. discover_plugins()        → plugin-level tools
5. discover_mcp_tools()      → external MCP server tools
6. Build AIAgent with model, provider, credentials
7. Enter REPL loop
```

### 6.2 User Input Processing

```
user types text
  → prompt_toolkit input
  → process_command(text)
     ├─ slash_command?    → dispatch to command handler (e.g. /model, /tools, /steer)
     └─ normal message?  → AIAgent.run_conversation(text)
                            └─ enters agent loop (Section 2)
```

### 6.3 Slash Commands (CLI)

Defined in a central registry (`hermes_cli/commands.py`):

- `/model`, `/provider` — switch model/provider
- `/tools`, `/toolson`, `/toolsoff` — toggle toolsets
- `/steer` — inject guidance into next tool result
- `/session`, `/new` — manage sessions
- `/memory`, `/skill` — manage memory/skills
- Skill commands from `~/.hermes/skills/*` — loaded dynamically

## 7. Key Data Flows

### 7.1 System Prompt Construction

Built once per session, cached for prompt prefix caching:

```
SYSTEM_PROMPT = (
    agent_identity (persona) +
    platform hints +
    environment hints (OS, cwd) +
    skill instructions (loaded from SKILL.md) +
    memory context block (from memory store) +
    context files (AGENTS.md / CLAUDE.md / .cursorrules) +
    tool use guidance (per-provider)
)
```

### 7.2 Token Budget & Context Compression

```python
# Per-turn budget tracking
iteration_budget = IterationBudget(max_iterations=90)
# Or: hard cap via max_iterations

# Context compression when exceeding threshold
if context_compressor.should_compress(approx_tokens):
    messages = compress_messages(messages)
    # Summarizes middle conversation turns while preserving
    # first N and last N messages unchanged
```

### 7.3 Fallback Chain

When the primary provider fails (429, 500, empty response, rate limit):

```python
# Try fallback providers in order
for fallback in fallback_chain:
    switch_client(fallback.base_url, fallback.api_key, fallback.model)
    retry_api_call()
```

### 7.4 Interrupt Handling

```python
# Thread-safe interrupt signal
interrupt_requested = threading.Event()

# Set on user interrupt (Ctrl+C, new message, /stop)
interrupt_requested.set()

# Checked at multiple points in the loop:
# - Before each API call
# - Between tool executions
# - During retry backoff sleep
```

## 8. Minimal Re-implementation Checklist

To build a working agent harness in any language, you need:

1. **Provider API Client** — POST to `/v1/chat/completions` with OpenAI-compatible format. Must handle streaming and non-streaming.

2. **Message Store** — List of `{role, content, tool_calls}` dicts with role alternation validation.

3. **Tool Registry** — Map of `name → {schema, handler}`. Schema in OpenAI function-calling JSON Schema format.

4. **Schema Builder** — Build the `tools` array from registry, respecting enabled/disabled toolset filters.

5. **Agent Loop** — The while-loop (Section 2): call LLM, parse tool_calls, execute tools, append results, repeat.

6. **Tool Executor** — Run tool handlers, coerce arg types, catch errors, return JSON strings.

7. **Guardrails** — Track repeated failed tool calls per turn, inject warnings or halt.

8. **Context Compressor** — Summarize middle turns when approaching context window limit.

9. **Argument Coercion** — Fix common LLM quirks: string→int, string→bool, bare scalar→array wrapping.

10. **Terminal UI (optional)** — Readline/prompt_toolkit input, tool result display, interrupt handling.

### Data Formats Required

```
Request:  POST /v1/chat/completions
          {"model": "...", "messages": [...], "tools": [...], "tool_choice": "auto"}

Response: {"choices": [{"message": {"role": "assistant", "content": "...",
                                    "tool_calls": [{"id": "call_xxx",
                                      "type": "function",
                                      "function": {"name": "...", "arguments": "{}"}}]},
                       "finish_reason": "stop"|"tool_calls"|"length"}]}

Tool Result: JSON string returned to model as
             {"role": "tool", "content": "json_string", "tool_call_id": "call_xxx", "name": "tool_name"}
```

## 9. Key Architectural Decisions

| Decision | Why |
|----------|-----|
| Single system prompt cached per session | Preserves Anthropic/OpenAI prompt caching prefixes |
| Tool results immer als JSON-String | Einfach zu serialisieren, zu speichern und zu analysieren |
| Registry pattern (self-registering tools) | Neue Tools müssen nur die Datei hinzufügen, keine zentrale Registrierung |
| OpenAI chat completions format | Universelle Kompatibilität mit den meisten Modellanbietern |
| Sequential by default, concurrent opt-in | Sicherheit: verhindert Race-Conditions bei sich überschneidenden Dateioperationen |
| 30s TTL on check_fn cache | Balance zwischen Reaktionsfähigkeit und Performance |
| Guardrails in-memory per turn | Verhindert Endlosschleifen ohne Zustand über Sitzungen hinweg |
