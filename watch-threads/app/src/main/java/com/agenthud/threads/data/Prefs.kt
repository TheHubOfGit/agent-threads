package com.agenthud.threads.data

import android.content.Context
import com.agenthud.threads.BuildConfig

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("agent_threads_prefs", Context.MODE_PRIVATE)

    /** Last bridge address that answered (found over Bonjour, or entered by hand). */
    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(value) = prefs.edit().putString("host", value.trim()).apply()

    var port: Int
        get() = prefs.getInt("port", BuildConfig.DEFAULT_PORT)
        set(value) = prefs.edit().putInt("port", value).apply()

    /** True when [host] was typed in (e.g. a Tailscale address): Bonjour never replaces it. */
    var hostPinned: Boolean
        get() = prefs.getBoolean("host_pinned", false)
        set(value) = prefs.edit().putBoolean("host_pinned", value).apply()

    var token: String
        get() = prefs.getString("token", BuildConfig.DEFAULT_AUTH_TOKEN) ?: BuildConfig.DEFAULT_AUTH_TOKEN
        set(value) = prefs.edit().putString("token", value.trim()).apply()

    /**
     * The relay that reaches the Mac from any network (https://…). Learned from the Mac while on the
     * home network, or typed in Settings. Empty: home network only.
     */
    var relayUrl: String
        get() = prefs.getString("relay_url", "") ?: ""
        set(value) = prefs.edit().putString("relay_url", value.trim()).apply()

    /** Provider key shown in the list ("" = all). */
    var providerFilter: String
        get() = prefs.getString("provider_filter", "") ?: ""
        set(value) = prefs.edit().putString("provider_filter", value).apply()
}
