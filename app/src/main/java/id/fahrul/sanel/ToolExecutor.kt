package id.fahrul.sanel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ToolExecutor {
    private const val TERMUX_PKG = "com.termux"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val RESULT_ACTION = "com.termux.RUN_COMMAND_RESULT"

    fun buildTools(): List<Map<String, Any>>? {
        if (!SettingsManager.termuxEnabled || !SettingsManager.termuxPermissionGranted) return null
        return listOf(
            mapOf(
                "type" to "function",
                "function" to mapOf(
                    "name" to "execute_command",
                    "description" to "Execute a shell command in Termux. Run terminal commands, scripts, manage files, install packages, etc. Uses Termux RUN_COMMAND intent.",
                    "parameters" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "command" to mapOf(
                                "type" to "string",
                                "description" to "The shell command to execute in Termux"
                            ),
                            "label" to mapOf(
                                "type" to "string",
                                "description" to "Short 3-5 word summary of what this command does (e.g. 'Install create-vite globally', 'Create Vite React project')"
                            ),
                            "timeout_seconds" to mapOf(
                                "type" to "integer",
                                "description" to "Max wait time in seconds (default: 30)"
                            )
                        ),
                        "required" to listOf("command")
                    )
                )
            )
        )
    }

    fun executeCommand(context: Context, command: String, timeoutSeconds: Int = 30): String {
        return try {
            executeViaTermuxIntent(context, command, timeoutSeconds)
        } catch (e: SecurityException) {
            "Permission denied: ${e.message}. Grant Termux permission in Settings."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private var requestCodeCounter = 0
    private fun nextRequestCode(): Int = ++requestCodeCounter

    private fun executeViaTermuxIntent(context: Context, command: String, timeoutSeconds: Int): String {
        val latch = CountDownLatch(1)
        var stdout = ""
        var stderr = ""
        var exitCode = -1

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val bundle = intent.getBundleExtra("result")
                if (bundle != null) {
                    stdout = bundle.getString("stdout", "")
                    stderr = bundle.getString("stderr", "")
                    exitCode = bundle.getInt("exitCode", -1)
                } else {
                    stdout = intent.getStringExtra("stdout") ?: ""
                    stderr = intent.getStringExtra("stderr") ?: ""
                    exitCode = intent.getIntExtra("exitCode", -1)
                }
                latch.countDown()
            }
        }

        val filter = IntentFilter(RESULT_ACTION)
        val flags = if (Build.VERSION.SDK_INT >= 34) Context.RECEIVER_EXPORTED else 0
        context.registerReceiver(receiver, filter, flags)

        try {
            val resultIntent = Intent(RESULT_ACTION).apply {
                `package` = context.packageName
            }
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.getBroadcast(
                    context, nextRequestCode(), resultIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            } else {
                PendingIntent.getBroadcast(
                    context, nextRequestCode(), resultIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            val intent = Intent(ACTION_RUN_COMMAND).apply {
                `package` = TERMUX_PKG
                putExtra(EXTRA_COMMAND_PATH, "/data/data/com.termux/files/usr/bin/bash")
                putExtra(EXTRA_ARGUMENTS, arrayOf("-l", "-c", "source ~/.bashrc 2>/dev/null; source ~/.profile 2>/dev/null; $command"))
                putExtra(EXTRA_WORKDIR, "/data/data/com.termux/files/home")
                putExtra(EXTRA_BACKGROUND, true)
                putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            val ok = latch.await(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            if (!ok) return "Command timed out after ${timeoutSeconds}s"

            return buildString {
                append("{\"exit_code\":$exitCode")
                if (stdout.isNotBlank()) append(",\"stdout\":${com.google.gson.Gson().toJson(stdout)}")
                if (stderr.isNotBlank()) append(",\"stderr\":${com.google.gson.Gson().toJson(stderr)}")
                append("}")
            }.trimEnd()
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }
}
