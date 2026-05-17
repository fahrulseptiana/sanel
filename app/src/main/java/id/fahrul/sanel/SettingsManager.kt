package id.fahrul.sanel

import android.content.Context
import android.content.SharedPreferences

object SettingsManager {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("sanel_prefs", Context.MODE_PRIVATE)
    }

    var endpoint: String
        get() = prefs.getString("endpoint", "https://api.openai.com/v1/chat/completions") ?: "https://api.openai.com/v1/chat/completions"
        set(v) = prefs.edit().putString("endpoint", v).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v).apply()

    var model: String
        get() = prefs.getString("model", "gpt-4o") ?: "gpt-4o"
        set(v) = prefs.edit().putString("model", v).apply()

    var temperature: Float
        get() = prefs.getFloat("temperature", 0.7f)
        set(v) = prefs.edit().putFloat("temperature", v).apply()

    var maxTokens: Int
        get() = prefs.getInt("max_tokens", 4096)
        set(v) = prefs.edit().putInt("max_tokens", v).apply()

    var thinkingEnabled: Boolean
        get() = prefs.getBoolean("thinking", false)
        set(v) = prefs.edit().putBoolean("thinking", v).apply()

    var thinkingBudget: Int
        get() = prefs.getInt("thinking_budget", 1024)
        set(v) = prefs.edit().putInt("thinking_budget", v).apply()

    var termuxEnabled: Boolean
        get() = prefs.getBoolean("termux_enabled", true)
        set(v) = prefs.edit().putBoolean("termux_enabled", v).apply()

    var termuxPermissionGranted: Boolean
        get() = prefs.getBoolean("termux_permission", false)
        set(v) = prefs.edit().putBoolean("termux_permission", v).apply()
}
