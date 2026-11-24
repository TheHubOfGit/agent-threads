package com.agenthud.threads.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Asks Android for Wi-Fi while Agent Threads is on screen (same approach as the desk HUD).
 *
 * Off the charger, Wear OS turns Wi-Fi off a few minutes after the screen goes dark, and with
 * Bluetooth disabled the watch then has no link to the bridge at all. A held network request makes
 * the system bring Wi-Fi back up as soon as the HUD is visible again, instead of minutes later.
 * The request is released when the HUD leaves the screen, so it costs nothing while the watch sleeps.
 * (The foreground service's WifiLock keeps an existing connection fast; it does not stop Wear OS
 * from switching Wi-Fi off.)
 */
class WifiRequester(
    context: Context,
    /** Route the app's sockets over Wi-Fi? Not when a typed-in address (e.g. Tailscale) may be elsewhere. */
    private val shouldBind: () -> Boolean = { true },
    private val onWifiAvailable: () -> Unit
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var passiveCallback: ConnectivityManager.NetworkCallback? = null

    // Without this, Wi-Fi power save delayed requests by 20+ seconds on a Pixel Watch 5. The lock is
    // held only while the app is on screen (low-latency mode needs a visible app anyway).
    private val wifiLock = context.getSystemService(android.net.wifi.WifiManager::class.java)
        ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "AgentThreads:fast")
        ?.apply { setReferenceCounted(false) }

    /**
     * Passively listens for Wi-Fi joining (e.g. walking back home) without waking the Wi-Fi radio.
     * Lets Bluetooth-only sessions notice home Wi-Fi and switch back to direct without holding Wi-Fi.
     */
    fun watchPassive() {
        val cm = cm ?: return
        if (passiveCallback != null) return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Wi-Fi joined (passive)")
                main.post(onWifiAvailable)
            }
        }
        runCatching {
            cm.registerNetworkCallback(req, cb)
            passiveCallback = cb
        }
    }

    fun unwatchPassive() {
        val cb = passiveCallback ?: return
        passiveCallback = null
        runCatching { cm?.unregisterNetworkCallback(cb) }
    }

    fun acquire() {
        runCatching { wifiLock?.acquire() }
        val cm = cm ?: return
        if (callback != null) return
        // The bridge is on the local network, so don't wait for an internet check to pass.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Route this app's sockets over Wi-Fi even if another network is the default.
                if (shouldBind()) runCatching { cm.bindProcessToNetwork(network) }
                Log.i(TAG, "Wi-Fi available")
                main.post(onWifiAvailable)
            }

            override fun onLost(network: Network) {
                if (cm.boundNetworkForProcess == network) runCatching { cm.bindProcessToNetwork(null) }
                Log.i(TAG, "Wi-Fi lost")
            }
        }
        try {
            cm.requestNetwork(request, cb)
            callback = cb
            Log.i(TAG, "Requested Wi-Fi")
        } catch (t: Throwable) {
            Log.w(TAG, "Wi-Fi request failed: ${t.message}")
        }
    }

    fun release() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
        val cb = callback ?: return
        callback = null
        runCatching { cm?.unregisterNetworkCallback(cb) }
        runCatching { cm?.bindProcessToNetwork(null) }
        Log.i(TAG, "Released Wi-Fi request")
    }

    private companion object {
        const val TAG = "AgentThreads"
    }
}
