package com.agenthud.threads.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * HTTP client for the Agent HUD bridge on the Mac. The wrist app only polls while it is on screen,
 * so there is no socket to keep alive in a pocket or on a walk.
 *
 * On home Wi-Fi it talks directly to the Mac (found over Bonjour `_agenthud._tcp` unless pinned).
 * Away from home (Bluetooth-only through the phone, LTE, or another Wi-Fi) it goes straight to the
 * relay over HTTPS without wasting time probing private LAN IPs, caches responses via ETag/304 so
 * unchanged polls transfer zero body bytes, and compresses voice uploads for slow Bluetooth links.
 */
class BridgeApi(private val context: Context, private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        // Longer than the slowest send on the Mac (codex queue: 30s), so a slow success isn't reported
        // as a failure that invites a second send.
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    // Unproven until the Mac answers: a remembered address may be stale.
    private val _link = MutableStateFlow(LinkState.SEARCHING)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    /** Mac clock minus watch clock, so "2m ago" is right even when the clocks disagree. */
    private val _clockOffsetMs = MutableStateFlow(0L)
    val clockOffsetMs: StateFlow<Long> = _clockOffsetMs.asStateFlow()

    val hostLabel: String get() = prefs.host.ifBlank { "searching" }

    /** How the last successful request reached the Mac. */
    enum class Route { DIRECT, RELAY }

    // Away from Wi-Fi with a relay configured: start directly on RELAY so cold start never hangs
    // trying a private home IP or spins up the watch's Wi-Fi radio.
    private val _route = MutableStateFlow(
        if (prefs.relayUrl.isNotBlank() && !prefs.hostPinned && !onWifi()) Route.RELAY else Route.DIRECT
    )
    val route: StateFlow<Route> = _route.asStateFlow()

    /** While on the relay, when to try the direct (home network) route again. */
    @Volatile private var nextDirectTryMs = 0L

    // At home a direct connection is <15ms; away on another Wi-Fi, fail fast and use the relay.
    private val directHttp = http.newBuilder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    // Wear OS Bluetooth proxy (Sysproxy) silently drops idle sockets within seconds. Keep idle pool
    // lifetime short, send HTTP/2 pings, and split timeouts between quick polls and long uploads/sends.
    private val relayPool = ConnectionPool(2, 20, TimeUnit.SECONDS)
    private val relayPollHttp = http.newBuilder()
        .connectionPool(relayPool)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(22, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val relayActionHttp = http.newBuilder()
        .connectionPool(relayPool)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(50, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    // ETag + last parsed JSON per GET cache key so unchanged polls return 304 (0 body bytes).
    private val etags = ConcurrentHashMap<String, String>()
    private val cachedJson = ConcurrentHashMap<String, JSONObject>()

    private fun relayBase(): HttpUrl? = prefs.relayUrl.takeIf { it.isNotBlank() }?.let { runCatching { it.toHttpUrl() }.getOrNull() }

    private data class RawResponse(val code: Int, val bytes: ByteArray, val etag: String?)

    /**
     * Sends one request to the Mac by the best route: directly on the home network, otherwise through
     * the relay (learned from the Mac while at home, or set in Settings).
     */
    private fun execute(
        path: String,
        query: Map<String, String>,
        body: RequestBody?,
        extraHeaders: Map<String, String> = emptyMap()
    ): RawResponse {
        val relay = relayBase()
        val host = prefs.host
        val now = System.currentTimeMillis()
        // Never attempt a private LAN address when Wi-Fi isn't connected (Bluetooth-only / LTE),
        // unless the user pinned a custom host (e.g. a Tailscale address).
        val canReachLan = host.isNotBlank() && (prefs.hostPinned || onWifi())
        val tryDirect = canReachLan && (relay == null || _route.value == Route.DIRECT)
        // On the relay while on Wi-Fi: quietly check if we're back on the home network.
        if (!tryDirect && canReachLan && now >= nextDirectTryMs) probeDirect(host)
        var lastError: Throwable = IllegalStateException("Looking for your Mac…")
        if (tryDirect) {
            var requestSent = false
            val trackedClient = (if (relay != null) directHttp else http).newBuilder()
                .eventListener(object : EventListener() {
                    override fun requestHeadersStart(call: Call) {
                        requestSent = true
                    }
                })
                .build()
            try {
                val url = HttpUrl.Builder().scheme("http").host(host).port(prefs.port).addPathSegments(path)
                    .apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
                trackedClient.newCall(request(url, body, extraHeaders)).execute().use { resp ->
                    resp.header("X-Relay-Url")?.let(::learnRelay)
                    val out = RawResponse(resp.code, resp.body?.bytes() ?: ByteArray(0), resp.header("ETag"))
                    if (_route.value != Route.DIRECT) Log.i(TAG, "Back on the home network")
                    _route.value = Route.DIRECT
                    return out
                }
            } catch (e: Throwable) {
                lastError = e
                // Fall back to the relay whenever we never transmitted a POST (guaranteed no double
                // send), or for any idempotent GET. Previously fragile message-string checks missed
                // OkHttp's "timeout" and Sysproxy SocketExceptions.
                if (relay == null || (body != null && requestSent)) throw e
                nextDirectTryMs = now + 60_000
            }
        }
        if (relay == null) throw lastError
        val url = relay.newBuilder().addPathSegments(path).apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val client = if (body == null) relayPollHttp else relayActionHttp
        fun callRelay(): RawResponse = client.newCall(request(url, body, extraHeaders)).execute().use { resp ->
            if (_route.value != Route.RELAY) Log.i(TAG, "Using the relay")
            _route.value = Route.RELAY
            if (nextDirectTryMs < now) nextDirectTryMs = now + 60_000
            RawResponse(resp.code, resp.body?.bytes() ?: ByteArray(0), resp.header("ETag"))
        }
        return try {
            callRelay()
        } catch (e: IOException) {
            // Over Bluetooth Sysproxy, a pooled socket can silently die; evict and retry GET once.
            if (body != null) throw e
            relayPool.evictAll()
            callRelay()
        }
    }

    private val probeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var probing = false

    /** Quietly tries the home route; if it answers, later requests go direct again. */
    private fun probeDirect(host: String) {
        if (probing) return
        probing = true
        nextDirectTryMs = System.currentTimeMillis() + 60_000
        probeScope.launch {
            try {
                val url = HttpUrl.Builder().scheme("http").host(host).port(prefs.port).addPathSegments("conversations")
                    .addQueryParameter("limit", "1").build()
                directHttp.newCall(request(url, null)).execute().use { resp ->
                    if (resp.code in 200..299) {
                        resp.header("X-Relay-Url")?.let(::learnRelay)
                        Log.i(TAG, "Home network is back; going direct")
                        _route.value = Route.DIRECT
                    }
                }
            } catch (_: Throwable) {
                // Still away from home.
            } finally {
                probing = false
            }
        }
    }

    private fun request(url: HttpUrl, body: RequestBody?, extraHeaders: Map<String, String> = emptyMap()) =
        Request.Builder()
            .url(url)
            .header("X-HUD-Token", prefs.token)
            .apply {
                extraHeaders.forEach { (k, v) -> header(k, v) }
                if (body != null) post(body)
            }
            .build()

    /** The Mac said where its relay is (only direct answers carry this). */
    private fun learnRelay(raw: String) {
        val clean = raw.trim().trimEnd('/')
        if (clean != prefs.relayUrl && runCatching { clean.toHttpUrl() }.isSuccess &&
            (clean.startsWith("https://") || clean.startsWith("http://"))
        ) {
            Log.i(TAG, "Learned the relay address")
            prefs.relayUrl = clean
        }
    }

    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var failures = 0

    // ---- discovery & lifecycle ------------------------------------------------------------------

    /** Called when the app returns to the foreground (wrist raise / open). */
    fun onForeground() {
        // Evict stale BT Sysproxy sockets that silently died while the watch slept.
        relayPool.evictAll()
        if (prefs.relayUrl.isNotBlank() && !prefs.hostPinned && !onWifi()) {
            _route.value = Route.RELAY
            stopDiscovery()
        } else if (prefs.host.isBlank()) {
            // Only run Bonjour/NSD when we don't already have the Mac's IP: Android 17 / Wear OS
            // shows a "Choose a device to connect: Mac Agent HUD" prompt on the paired phone
            // whenever the watch runs mDNS discovery.
            startDiscovery()
        }
    }

    fun startDiscovery() {
        if (discovery != null || prefs.hostPinned) return
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        // Search only on Wi-Fi: off Wi-Fi the search goes through the phone's Bluetooth proxy,
        // and the Android 17 phone answers it with a "Choose a device to connect" picker.
        val wifi = wifiNetwork() ?: return
        nsd = mgr
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("_agenthud")) return
                @Suppress("DEPRECATION")
                mgr.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = info.host?.hostAddress ?: return
                        if (host.contains(':')) return // skip IPv6 link-local; the IPv4 record follows
                        if (prefs.hostPinned) return
                        if (host != prefs.host || _link.value != LinkState.CONNECTED) {
                            Log.i(TAG, "Found bridge at $host:${info.port}")
                            prefs.host = host
                            prefs.port = info.port
                            if (_route.value == Route.RELAY) probeDirect(host)
                        }
                        // Stop discovering as soon as we have an IPv4 address so Android doesn't
                        // keep re-triggering the paired phone's local-network device picker.
                        stopDiscovery()
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { discovery = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discovery = listener
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mgr.discoverServices("_agenthud._tcp.", NsdManager.PROTOCOL_DNS_SD, wifi, { it.run() }, listener)
            } else {
                mgr.discoverServices("_agenthud._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            }
        }
            .onFailure { discovery = null }
    }

    fun stopDiscovery() {
        val l = discovery ?: return
        discovery = null
        runCatching { nsd?.stopServiceDiscovery(l) }
    }

    /** Wi-Fi (re)connected: forget earlier failures and check whether we're back on the home LAN. */
    fun onWifiAvailable() {
        failures = 0
        if (_link.value == LinkState.OFFLINE) _link.value = LinkState.SEARCHING
        if (prefs.host.isBlank() && !prefs.hostPinned) {
            stopDiscovery()
            startDiscovery()
        } else if (_route.value == Route.RELAY && prefs.host.isNotBlank()) {
            probeDirect(prefs.host)
        }
    }

    fun onWifi(): Boolean = wifiNetwork() != null

    private fun wifiNetwork(): android.net.Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    fun hasInternet(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        if (activeCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) return true
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    }

    companion object {
        /**
         * "host", "host:port" or a pasted "http://host:port/..." -> (host, port), or null if unusable.
         * Bare IPv6 isn't supported (use the Mac's IPv4 or Tailscale 100.x address).
         */
        fun parseAddress(raw: String, defaultPort: Int): Pair<String, Int>? {
            val s = raw.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
            if (s.isEmpty() || s.count { it == ':' } > 1) return null
            val host = s.substringBefore(':').trim()
            val portText = s.substringAfter(':', "").trim()
            val port = if (portText.isEmpty()) defaultPort else portText.toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535 || !host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
            return host to port
        }

        /**
         * Converts a standard 16kHz 16-bit mono PCM WAV to gzipped 8-bit ITU-T G.711 µ-law (~2.5-3x
         * smaller for fast, reliable uploads over Wear OS Bluetooth proxy).
         */
        fun wavToGzippedMulaw(wav: ByteArray): ByteArray? {
            if (wav.size <= 44 || wav[0] != 'R'.code.toByte() || wav[1] != 'I'.code.toByte()) return null
            val pcmBytes = wav.size - 44
            val samples = pcmBytes / 2
            val mulaw = ByteArray(samples)
            for (i in 0 until samples) {
                val lo = wav[44 + i * 2].toInt() and 0xFF
                val hi = wav[44 + i * 2 + 1].toInt()
                val pcm = (hi shl 8) or lo
                mulaw[i] = linearToMulaw(pcm)
            }
            val out = ByteArrayOutputStream(samples)
            GZIPOutputStream(out).use { it.write(mulaw) }
            return out.toByteArray()
        }

        private fun linearToMulaw(sample: Int): Byte {
            val bias = 0x84
            val clip = 32635
            var sign = 0
            var pcm = sample
            if (pcm < 0) {
                pcm = -pcm
                sign = 0x80
            }
            if (pcm > clip) pcm = clip
            pcm += bias
            var exponent = 7
            var expMask = 0x4000
            while (exponent > 0 && (pcm and expMask) == 0) {
                exponent--
                expMask = expMask shr 1
            }
            val mantissa = (pcm shr (exponent + 3)) and 0x0F
            return (sign or (exponent shl 4) or mantissa).inv().toByte()
        }
    }

    fun setManualHost(host: String, port: Int) {
        prefs.host = host
        prefs.port = port
        prefs.hostPinned = host.isNotBlank()
        failures = 0
        etags.clear()
        cachedJson.clear()
        _route.value = if (host.isBlank() && prefs.relayUrl.isNotBlank() && !onWifi()) Route.RELAY else Route.DIRECT
        nextDirectTryMs = 0L
        _lastError.value = ""
        _link.value = LinkState.SEARCHING
        if (host.isBlank()) {
            stopDiscovery()
            if (onWifi() || prefs.relayUrl.isBlank()) startDiscovery()
        } else {
            stopDiscovery()
        }
    }

    // ---- requests -------------------------------------------------------------------------------

    suspend fun listConversations(limit: Int = 40): Result<List<Conversation>> =
        get("conversations", mapOf("limit" to limit.toString()), useEtag = true).map { json ->
            val arr = json.optJSONArray("conversations")
            buildList {
                if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(Conversation.from(it)) }
            }
        }

    suspend fun conversation(id: String, limit: Int): Result<ConversationPage> =
        get("conversation", mapOf("id" to id, "limit" to limit.toString()), useEtag = true).map { json ->
            val arr = json.optJSONArray("messages")
            ConversationPage(
                conversation = Conversation.from(json.optJSONObject("conversation") ?: JSONObject()),
                messages = buildList {
                    if (arr != null) for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(ChatMessage.from(it)) }
                },
                hasMore = json.optBoolean("hasMore", false)
            )
        }

    /** Sends [text] to exactly this conversation. Success carries the Mac's short status line. */
    /** [clientId]: one id per composed reply; a retry with the same id is never sent twice. */
    suspend fun reply(id: String, text: String, clientId: String): Result<String> =
        post("conversation/reply", JSONObject().put("id", id).put("text", text).put("clientId", clientId).toString().json())
            .map { it.optString("message", "Sent") }

    /** Answers an open question or approval (the desk HUD's /action: proceed, deny, reply). */
    suspend fun action(id: String, action: String, text: String? = null): Result<String> {
        val body = JSONObject().put("threadId", id).put("action", action)
        if (!text.isNullOrBlank()) body.put("text", text)
        return post("action", body.toString().json()).map { it.optString("message", "Sent") }
    }

    /** A JPEG preview of an image from a conversation, about [widthPx] wide (made on the Mac). */
    suspend fun image(token: String, widthPx: Int): Result<ByteArray> = withContext(Dispatchers.IO) {
        if (!token.matches(Regex("[0-9a-f]{16,64}"))) return@withContext Result.failure(IllegalStateException("No image"))
        try {
            val resp = execute("image", mapOf("t" to token, "w" to widthPx.toString()), null)
            if (resp.code in 200..299 && resp.bytes.isNotEmpty()) Result.success(resp.bytes)
            else Result.failure(IllegalStateException("Preview unavailable (${resp.code})"))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** Speech to text on the Mac (whisper.cpp), compressed over relay/Bluetooth for fast uploads. */
    suspend fun transcribe(wav: ByteArray): Result<String> {
        val compact = if (_route.value == Route.RELAY || !onWifi()) wavToGzippedMulaw(wav) else null
        val result = if (compact != null) {
            call(
                "voice-transcribe",
                emptyMap(),
                compact.toRequestBody("audio/x-mulaw".toMediaType()),
                extraHeaders = mapOf("Content-Encoding" to "gzip")
            )
        } else {
            post("voice-transcribe", wav.toRequestBody("audio/wav".toMediaType()))
        }
        return result.mapCatching { json ->
            json.optString("text", "").trim().ifEmpty { throw IllegalStateException("No speech heard") }
        }
    }

    private fun String.json(): RequestBody = toRequestBody("application/json".toMediaType())

    private suspend fun get(path: String, query: Map<String, String>, useEtag: Boolean = false): Result<JSONObject> =
        call(path, query, null, useEtag = useEtag)

    private suspend fun post(path: String, body: RequestBody): Result<JSONObject> =
        call(path, emptyMap(), body)

    private suspend fun call(
        path: String,
        query: Map<String, String>,
        body: RequestBody?,
        extraHeaders: Map<String, String> = emptyMap(),
        useEtag: Boolean = false
    ): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            val host = prefs.host
            if (host.isBlank() && relayBase() == null) {
                startDiscovery()
                return@withContext Result.failure(IllegalStateException("Looking for your Mac…"))
            }
            val cacheKey = if (useEtag && body == null) {
                "$path?${query.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }}"
            } else null
            val headers = buildMap {
                putAll(extraHeaders)
                if (cacheKey != null && cachedJson.containsKey(cacheKey)) {
                    etags[cacheKey]?.let { put("If-None-Match", it) }
                }
            }
            try {
                val resp = execute(path, query, body, headers)
                val code = resp.code
                run {
                    if (code == 304 && cacheKey != null) {
                        val cached = cachedJson[cacheKey]
                        if (cached != null) {
                            failures = 0
                            _link.value = LinkState.CONNECTED
                            _lastError.value = ""
                            return@run Result.success(cached)
                        }
                    }
                    val text = String(resp.bytes, Charsets.UTF_8)
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
                    // Any answer at all means the Mac is reachable, even if this request was refused.
                    failures = 0
                    _link.value = LinkState.CONNECTED
                    json.optLong("serverTimeMs", 0L).takeIf { it > 0 }?.let {
                        _clockOffsetMs.value = it - System.currentTimeMillis()
                    }
                    if (code == 503 && _route.value == Route.RELAY) {
                        // The relay answered, but the Mac behind it didn't: that's offline, not connected.
                        _link.value = LinkState.OFFLINE
                        _lastError.value = "The Mac is offline (asleep, or Agent HUD isn't running)"
                        return@run Result.failure(IllegalStateException("The Mac is offline (asleep, or Agent HUD isn't running)"))
                    }
                    if (code == 401) {
                        _lastError.value = "The Mac refused this watch's token"
                        return@run Result.failure(IllegalStateException("The Mac refused this watch's token"))
                    }
                    if (code == 404 && path == "conversations") {
                        return@run Result.failure(IllegalStateException("Update Agent HUD on your Mac: its bridge doesn't have conversations yet"))
                    }
                    if (code !in 200..299 || json.optBoolean("ok", true) == false) {
                        val msg = json.optString("message").ifBlank { json.optString("error") }
                            .ifBlank { "The Mac returned an error (${code})" }
                        return@run Result.failure(IllegalStateException(msg))
                    }
                    if (cacheKey != null && !resp.etag.isNullOrBlank()) {
                        etags[cacheKey] = resp.etag
                        cachedJson[cacheKey] = json
                        if (cachedJson.size > 24) {
                            cachedJson.keys.firstOrNull { it != cacheKey }?.let {
                                cachedJson.remove(it)
                                etags.remove(it)
                            }
                        }
                    }
                    _lastError.value = ""
                    Result.success(json)
                }
            } catch (e: Throwable) {
                failures++
                // Without a relay the Mac is only reachable on its own network.
                val noWifi = !prefs.hostPinned && relayBase() == null && !onWifi()
                val msg = when {
                    noWifi -> "Waiting for Wi-Fi…"
                    relayBase() != null -> "Can't reach the Mac (home network or relay)"
                    else -> "Can't reach the Mac at $host"
                }
                // One blip on a working link is forgiven; an address that never answered is offline at once.
                val neverAnswered = _link.value == LinkState.SEARCHING
                if (failures >= 2 || noWifi || neverAnswered) {
                    _link.value = LinkState.OFFLINE
                    _lastError.value = msg
                    // Only re-run Bonjour if we have no host or direct home-only failed repeatedly
                    // (DHCP change); avoids triggering Android 17's paired-phone NSD prompt.
                    if (!prefs.hostPinned && (prefs.host.isBlank() || (failures >= 3 && onWifi() && relayBase() == null))) {
                        startDiscovery()
                    }
                }
                Result.failure(IllegalStateException(if (noWifi) msg else "Can't reach the Mac"))
            }
        }

    private val TAG = "AgentThreads"
}
