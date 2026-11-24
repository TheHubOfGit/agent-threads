package com.agenthud.threads

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.agenthud.threads.data.BridgeApi
import com.agenthud.threads.data.Prefs
import com.agenthud.threads.data.WifiRequester
import com.agenthud.threads.ui.AppState
import com.agenthud.threads.ui.ThreadsApp

/**
 * Agent Threads: the on-the-go companion to the desk HUD. Browse recent agent conversations from
 * every provider, read them, and reply by voice, keyboard or quick reply.
 *
 * Everything network-related runs only while the app is on screen: Wi-Fi is requested and the Mac
 * searched for in onStart, and both are released in onStop.
 */
class MainActivity : ComponentActivity() {
    private lateinit var state: AppState
    private lateinit var wifi: WifiRequester

    private val localNetworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            state.api.stopDiscovery()
            state.api.startDiscovery()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // One state per process: Android recreating the screen (font size, locale) keeps the loaded
        // conversations, picks and pending replies, and doesn't buzz again for questions already seen.
        state = shared ?: run {
            val prefs = Prefs(applicationContext)
            AppState(BridgeApi(applicationContext, prefs), prefs) { buzz?.invoke() }
        }.also { shared = it }
        buzz = { buzzForQuestion() }
        val prefs = state.prefs
        val api = state.api
        // Only bind the process to Wi-Fi when there's no relay (home-only mode); otherwise binding to
        // a weak/captive Wi-Fi away from home would cut off Bluetooth/LTE internet.
        wifi = WifiRequester(applicationContext, shouldBind = { !prefs.hostPinned && prefs.relayUrl.isBlank() }) { api.onWifiAvailable() }
        applyHostExtra(intent)
        setContent { ThreadsApp(state) }
        followRoute()
        requestLocalNetworkIfNeeded()
    }

    /** Two short pulses: a conversation is waiting on you. */
    private fun buzzForQuestion() {
        val vib = getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator ?: return
        runCatching {
            vib.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60), -1))
        }
    }

    /** Android 17 made local-network access a runtime permission; without it the Mac is unreachable. */
    private fun requestLocalNetworkIfNeeded() {
        if (Build.VERSION.SDK_INT < 37) return
        if (checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED) return
        localNetworkPermission.launch(LOCAL_NETWORK)
    }

    private companion object {
        // Not in the SDK 36 headers this app compiles against.
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        private var shared: AppState? = null
        /** The visible activity's buzz; the shared state outlives any one activity. */
        private var buzz: (() -> Unit)? = null
    }

    override fun onDestroy() {
        if (isFinishing) buzz = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        applyHostExtra(intent)
    }

    /** Testing: `am start -n com.agenthud.threads/.MainActivity --es host 192.168.1.20:8765` ("" = automatic). */
    private fun applyHostExtra(intent: android.content.Intent?) {
        val raw = intent?.getStringExtra("host") ?: return
        val h = raw.substringBefore(':').trim()
        val p = raw.substringAfter(':', "").toIntOrNull() ?: BuildConfig.DEFAULT_PORT
        state.api.setManualHost(h, p)
    }

    override fun onStart() {
        super.onStart()
        state.api.onForeground()
        wifi.watchPassive()
    }

    /**
     * Wi-Fi only when it's needed. Talking to the Mac directly (home network) holds Wi-Fi low-latency
     * mode, and if the watch has no internet at all (no Bluetooth, no LTE) we wake Wi-Fi. When away
     * on Bluetooth-only or LTE with a relay configured, Wi-Fi stays untouched so Wear OS doesn't spin
     * up the radio or hijack Bluetooth internet.
     */
    private fun followRoute() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                try {
                    state.api.route.collect { route ->
                        val needWifi = state.prefs.relayUrl.isBlank() ||
                            (route == BridgeApi.Route.DIRECT && (state.api.onWifi() || !state.api.hasInternet()))
                        if (needWifi) wifi.acquire() else wifi.release()
                    }
                } finally {
                    wifi.release()
                }
            }
        }
    }

    override fun onStop() {
        wifi.unwatchPassive()
        state.api.stopDiscovery()
        super.onStop()
    }
}
