package id.fahrul.sanel

import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object LLMClient {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    fun streamChat(
        messages: List<Map<String, Any>>,
        tools: List<Map<String, Any>>?,
        onToken: (String) -> Unit,
        onReasoningToken: (String) -> Unit,
        onToolCall: (id: String, name: String, args: String) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
        rawLogPath: String? = null,
        forceToolChoice: Boolean = false,
        systemPrompt: String? = null
    ) {
        val body = buildJsonBody(messages, tools, forceToolChoice, systemPrompt)
        // Log request body for debugging
        try {
            val logFile = if (rawLogPath != null) java.io.File(rawLogPath.replace("_stream.jsonl", "_request.json")) else null
            logFile?.let { f -> f.parentFile?.mkdirs(); f.writeText(body) }
        } catch (_: Exception) {}
        val request = Request.Builder()
            .url(SettingsManager.endpoint)
            .header("Authorization", "Bearer ${SettingsManager.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                onError("Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    onError("HTTP ${response.code}: $errorBody")
                    return
                }

                try {
                    val reader = BufferedReader(InputStreamReader(response.body?.byteStream(), "UTF-8"))
                    var line: String?
                    var doneCalled = false

                    // Tool call accumulators — support multiple tool calls by index
                    val pendingToolCalls = mutableMapOf<Int, MutableMap<String, String>>()
                    // Content accumulator for detecting XML-wrapped tool calls
                    val contentAccumulator = StringBuilder()
                    var xmlToolCallDetected = false

                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        if (!l.startsWith("data: ")) continue

                        // Log raw SSE data for debugging
                        val rawLine = l.removePrefix("data: ").trim()
                        if (rawLine != "[DONE]") {
                            rawLogPath?.let { path ->
                                try {
                                    java.io.File(path).appendText("$rawLine\n")
                                } catch (_: Exception) {}
                            }
                        }

                        val data = rawLine
                        if (data == "[DONE]") {
                            if (!doneCalled) { doneCalled = true; onDone() }
                            return
                        }

                        try {
                            val parsed = gson.fromJson(data, Map::class.java)
                            @Suppress("UNCHECKED_CAST")
                            val choices = parsed["choices"] as? List<Map<String, Any>>
                            val delta = choices?.firstOrNull()?.get("delta") as? Map<String, Any>

                            // Content
                            delta?.let { d ->
                                d["content"]?.let { content ->
                                    val contentStr = content.toString()

                                    // Accumulate all content across chunks for pattern matching
                                    contentAccumulator.append(contentStr)
                                    val accumulated = contentAccumulator.toString()

                                    // Pattern headers to match (streamed in arbitrary chunks)
                                    val toolCallHeader = "TOOLCALL:execute_command("
                                    val executeHeader = "EXECUTE:"

                                    // Check if accumulated matches or is a prefix of any pattern
                                    val isToolCallPrefix = toolCallHeader.startsWith(accumulated) && accumulated.length < toolCallHeader.length
                                    val isExecutePrefix = executeHeader.startsWith(accumulated) && accumulated.length < executeHeader.length
                                    val isXmlPrefix = accumulated.contains(Regex("<[lL]ongcat|</[lL]ongcat")) && !accumulated.contains("</longcat_tool_call>")

                                    // Does current accumulated already START with a pattern?
                                    val startsWithToolCall = accumulated.startsWith(toolCallHeader)
                                    val startsWithExecute = accumulated.startsWith(executeHeader)

                                    // Order matters: check COMPLETE patterns first, then prefixes, then plain text
                                    if (accumulated.contains("</longcat_tool_call>")) {
                                        // Complete XML tool call detected
                                        xmlToolCallDetected = true
                                        val xmlTagStart = Regex("<[lL]ongcat|</[lL]ongcat")
                                        val match = xmlTagStart.find(accumulated)
                                        if (match != null && match.range.first > 0) {
                                            val narrative = accumulated.substring(0, match.range.first).trim()
                                            if (narrative.isNotEmpty()) onToken(narrative)
                                        }
                                        // Extract command from <longcat_arg_value>...</longcat_arg_value>
                                        val cmdMatch = Regex("<longcat_arg_value>(.*?)</longcat_arg_value>", RegexOption.DOT_MATCHES_ALL).find(accumulated)
                                        val cmd = cmdMatch?.groupValues?.get(1)?.trim() ?: ""
                                        if (cmd.isNotEmpty()) {
                                            onToolCall("call_${System.nanoTime()}", "execute_command", """{"command":"$cmd"}""")
                                        }
                                    } else if (startsWithToolCall) {
                                        // Full TOOLCALL header detected — extract command from JSON
                                        val jsonStr = accumulated.removePrefix("TOOLCALL:execute_command(")
                                        try {
                                            val parsed = com.google.gson.Gson().fromJson(jsonStr, Map::class.java)
                                            val cmd = parsed["command"]?.toString() ?: ""
                                            if (cmd.isNotEmpty()) {
                                                xmlToolCallDetected = true
                                                onToolCall("call_${System.nanoTime()}", "execute_command", """{"command":"$cmd"}""")
                                            }
                                        } catch (_: Exception) {}
                                    } else if (accumulated.contains("TOOLCALL:execute_command(")) {
                                        // TOOLCALL embedded in narrative (e.g. "Let me...TOOLCALL:execute_command(...)")
                                        val idx = accumulated.indexOf("TOOLCALL:execute_command(")
                                        if (idx > 0) {
                                            val narrative = accumulated.substring(0, idx).trim()
                                            if (narrative.isNotEmpty()) onToken(narrative)
                                        }
                                        val embeddedJson = accumulated.substring(idx).removePrefix("TOOLCALL:execute_command(")
                                        try {
                                            val parsed = com.google.gson.Gson().fromJson(embeddedJson, Map::class.java)
                                            val cmd = parsed["command"]?.toString() ?: ""
                                            if (cmd.isNotEmpty()) {
                                                xmlToolCallDetected = true
                                                onToolCall("call_${System.nanoTime()}", "execute_command", """{"command":"$cmd"}""")
                                            }
                                        } catch (_: Exception) {}
                                    } else if (startsWithExecute) {
                                        // Provider echoes EXECUTE: format — suppress
                                        xmlToolCallDetected = true
                                    } else if (isToolCallPrefix || isExecutePrefix || isXmlPrefix) {
                                        // Prefix-buffering — might become a pattern, don't forward yet
                                        // else: still prefix-buffering, wait for more chunks
                                    } else {
                                        // Plain text narrative — forward it
                                        onToken(contentStr)
                                        contentAccumulator.setLength(0)
                                    }
                                }
                                d["reasoning_content"]?.let { onReasoningToken(it.toString()) }
                                d["thinking"]?.let { onReasoningToken(it.toString()) }

                                // Tool calls in delta — handle multiple by index
                                @Suppress("UNCHECKED_CAST")
                                val tcs = d["tool_calls"] as? List<Map<String, Any>>
                                tcs?.forEach { tc ->
                                    val index = (tc["index"] as? Number)?.toInt() ?: 0
                                    val entry = pendingToolCalls.getOrPut(index) { mutableMapOf() }
                                    tc["id"]?.let { if (!entry.containsKey("id")) entry["id"] = it.toString() }
                                    @Suppress("UNCHECKED_CAST")
                                    val fn = tc["function"] as? Map<String, Any>
                                    fn?.let { f ->
                                        f["name"]?.let { if (!entry.containsKey("name")) entry["name"] = it.toString() }
                                        f["arguments"]?.let { args ->
                                            entry["args"] = (entry["args"] ?: "") + args.toString()
                                        }
                                    }
                                }
                            }

                            // Check finish_reason
                            choices?.firstOrNull()?.let { c ->
                                val fr = c["finish_reason"]
                                if (fr != null && !doneCalled && !xmlToolCallDetected) {
                                    doneCalled = true
                                    if (fr == "tool_calls" && pendingToolCalls.isNotEmpty()) {
                                        // Fire onToolCall for each pending tool call
                                        for ((_, entry) in pendingToolCalls) {
                                            val id = entry["id"] ?: "call_${System.nanoTime()}"
                                            val name = entry["name"] ?: ""
                                            val args = entry["args"] ?: ""
                                            if (name.isNotEmpty()) {
                                                onToolCall(id, name, args)
                                            }
                                        }
                                    } else {
                                        onDone()
                                    }
                                    return
                                }
                            }
                        } catch (_: Exception) {
                            // skip parse errors
                        }
                    }
                    if (!doneCalled && !xmlToolCallDetected) onDone()
                } catch (e: Exception) {
                    onError("Stream error: ${e.message}")
                }
            }
        })
    }

    private fun buildJsonBody(
        apiMessages: List<Map<String, Any>>,
        tools: List<Map<String, Any>>?,
        forceToolChoice: Boolean = false,
        systemPrompt: String? = null
    ): String {
        val body = mutableMapOf<String, Any?>(
            "model" to SettingsManager.model,
            "stream" to true,
            "temperature" to SettingsManager.temperature,
            "max_tokens" to SettingsManager.maxTokens
        )
        val msgs = mutableListOf<Map<String, Any>>()
        if (!systemPrompt.isNullOrEmpty()) {
            msgs.add(mapOf("role" to "system", "content" to systemPrompt))
        }
        msgs.addAll(apiMessages)
        body["messages"] = msgs
        if (tools != null && tools.isNotEmpty()) {
            body["tools"] = tools
            if (forceToolChoice) {
                body["tool_choice"] = "required"
            }
        }
        if (SettingsManager.thinkingEnabled) {
            body["thinking"] = mapOf("type" to "enabled")
        } else {
            body["thinking"] = mapOf("type" to "disabled")
        }
        return gson.toJson(body)
    }
}
