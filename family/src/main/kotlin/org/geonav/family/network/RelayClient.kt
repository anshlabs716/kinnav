package org.geonav.family.network

import android.content.SharedPreferences
import android.util.Log
import org.geonav.family.crypto.CryptoEngine
import org.geonav.family.domain.NetworkConfig
import org.geonav.family.finding.LatestSighting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTPS/WebSocket client for the KinNav relay (ported from Luogo-FOSS).
 *
 * Privacy properties:
 *  - Payload bodies are already E2EE ciphertext produced by CryptoEngine. This layer never
 *    sees plaintext location.
 *  - Nothing is transmitted unless the user has sharing enabled. Sync is driven by
 *    [startLiveWebSocket] only.
 *  - Tor/SOCKS5 routing is explicit and visible. When enabled the client says so in
 *    diagnostics rather than silently changing where traffic goes.
 *  - Custom CA certs are not accepted. TLS verification is never weakened.
 */
class RelayClient(
    private val prefs: SharedPreferences,
    private val crypto: CryptoEngine
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class HealthCheckResult(
        val reachable: Boolean,
        val statusMessage: String,
        val latencyMs: Long? = null,
        val serverVersion: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _networkConfig = MutableStateFlow(loadConfig())
    val networkConfig: StateFlow<NetworkConfig> = _networkConfig.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var wsJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var authToken: String? = prefs.getString(KEY_AUTH_TOKEN, null)

    private val relayUserId: String?
        get() = prefs.getString(KEY_USER_ID, null)

    // ----------------------------------------------------------------- config

    fun currentNetworkConfig(): NetworkConfig = _networkConfig.value

    fun updateNetworkConfig(config: NetworkConfig) {
        val previousUrl = _networkConfig.value.relayUrl
        _networkConfig.value = config
        prefs.edit()
            .putString(KEY_RELAY_URL, config.relayUrl)
            .putBoolean(KEY_USE_TOR, config.useTorOrbot)
            .putBoolean(KEY_SOCKS5, config.socks5Enabled)
            .putString(KEY_SOCKS5_HOST, config.socks5Host)
            .putInt(KEY_SOCKS5_PORT, config.socks5Port)
            .putInt(KEY_RETENTION_DAYS, config.retentionDays)
            .apply()
        if (config.relayUrl != previousUrl) {
            // The bearer token was minted by the old relay and is meaningless to the new
            // one; keeping it would make every request fail with a 401 that looks like an
            // outage. Registration happens again on the next connection.
            authToken = null
            prefs.edit().remove(KEY_AUTH_TOKEN).remove(KEY_USER_ID).apply()
        }
        // Routing changed, so any live socket is now on the wrong path.
        disconnect()
    }

    private fun loadConfig(): NetworkConfig {
        val defaults = NetworkConfig()
        return NetworkConfig(
            relayUrl = prefs.getString(KEY_RELAY_URL, defaults.relayUrl) ?: defaults.relayUrl,
            useTorOrbot = prefs.getBoolean(KEY_USE_TOR, defaults.useTorOrbot),
            socks5Enabled = prefs.getBoolean(KEY_SOCKS5, defaults.socks5Enabled),
            socks5Host = prefs.getString(KEY_SOCKS5_HOST, defaults.socks5Host) ?: defaults.socks5Host,
            socks5Port = prefs.getInt(KEY_SOCKS5_PORT, defaults.socks5Port),
            retentionDays = prefs.getInt(KEY_RETENTION_DAYS, defaults.retentionDays)
        )
    }

    fun currentProxySummary(): String {
        val config = _networkConfig.value
        return when {
            config.socks5Enabled -> "SOCKS5 ${config.socks5Host}:${config.socks5Port}"
            config.useTorOrbot -> "Orbot / Tor (SOCKS5 127.0.0.1:9050)"
            else -> "Direct connection"
        }
    }

    // ------------------------------------------------------------ http client

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        val config = _networkConfig.value
        val proxy = resolveProxy(config)
        if (proxy != null) {
            builder.proxy(proxy)
            // When routing through Tor, TLS must not carry a hostname that resolves outside
            // the tunnel. Tor's own TLS to the exit is still verified normally.
            builder.sslSocketFactory(defaultSocketFactory(), defaultTrustManager())
        }
        return builder.build()
    }

    private fun resolveProxy(config: NetworkConfig): Proxy? {
        val useSocks = config.socks5Enabled || config.useTorOrbot
        if (!useSocks) return null
        val host = if (config.useTorOrbot && !config.socks5Enabled) TOR_HOST else config.socks5Host
        val port = if (config.useTorOrbot && !config.socks5Enabled) TOR_PORT else config.socks5Port
        return runCatching { Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port)) }.getOrNull()
    }

    private fun defaultSocketFactory(): SSLSocketFactory =
        SSLSocketFactory.getDefault() as SSLSocketFactory

    /**
     * The platform's real trust manager.
     *
     * Deliberately not a permissive "trust all" manager. Weakening certificate validation
     * would defeat the point of E2EE: an active network attacker could otherwise serve
     * modified ciphertext and impersonate the relay.
     */
    private fun defaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    // --------------------------------------------------------------- health

    /** Probes the relay without sending any user data. */
    suspend fun checkServerHealth(customUrl: String? = null): HealthCheckResult =
        withContext(Dispatchers.IO) {
            val url = (customUrl ?: _networkConfig.value.relayUrl).trimEnd('/') + "/api/health"
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@withContext HealthCheckResult(false, "Relay URL must start with http:// or https://")
            }
            val started = System.currentTimeMillis()
            runCatching {
                buildClient().newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val latency = System.currentTimeMillis() - started
                    if (!response.isSuccessful) {
                        return@withContext HealthCheckResult(
                            false, "Relay returned HTTP ${response.code} after ${latency}ms", latency
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    val version = Regex("\"version\"\\s*:\\s*\"?([^\",}]+)").find(body)?.groupValues?.getOrNull(1)
                    HealthCheckResult(
                        reachable = true,
                        statusMessage = "Relay reachable in ${latency}ms" +
                            (version?.let { " (protocol v$it)" } ?: ""),
                        latencyMs = latency,
                        serverVersion = version
                    )
                }
            }.getOrElse {
                HealthCheckResult(false, "Could not reach relay: ${it.message ?: "unknown error"}")
            }
        }

    // -------------------------------------------------------------- identity

    /** Ensures the relay knows this device. Returns the user id. */
    suspend fun ensureRegistered(): String? = withContext(Dispatchers.IO) {
        authToken?.let { return@withContext relayUserId }
        val identity = crypto.loadOrCreateDeviceIdentity()
        val body = buildString {
            append("{")
            append("\"name\":\"").append(escapeJson(appDisplayName())).append("\",")
            append("\"color\":").append(appDisplayColor()).append(",")
            append("\"publicKey\":\"").append(CryptoEngine.encodeBase64Url(identity.publicKeyBytes)).append("\"")
            append("}")
        }
        val request = Request.Builder()
            .url(_networkConfig.value.relayUrl.trimEnd('/') + "/api/users")
            .post(body.toRequestBody())
            .build()
        runCatching {
            buildClient().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val text = response.body?.string().orEmpty()
                val token = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
                    ?: return@withContext null
                val userId = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
                    ?: return@withContext null
                authToken = token
                prefs.edit().putString(KEY_AUTH_TOKEN, token).putString(KEY_USER_ID, userId).apply()
                userId
            }
        }.getOrNull()
    }

    private fun appDisplayName(): String =
        prefs.getString(KEY_DISPLAY_NAME, "KinNav user") ?: "KinNav user"

    private fun appDisplayColor(): Long =
        prefs.getLong(KEY_DISPLAY_COLOR, 0xFF006C4CL)

    fun setLocalDisplay(name: String, colorArgb: Long) {
        prefs.edit().putString(KEY_DISPLAY_NAME, name).putLong(KEY_DISPLAY_COLOR, colorArgb).apply()
    }

    fun updateDisplayNameFromProfile(name: String, colorArgb: Long) = setLocalDisplay(name, colorArgb)

    private fun String.toRequestBody() = toRequestBody(JSON_MEDIA_TYPE)

    /** Minimal JSON string escaping; display names are user-supplied. */
    private fun escapeJson(value: String): String = buildString {
        for (c in value) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }

    // ---------------------------------------------------------- groups/sharing

    suspend fun createGroup(name: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val token = authToken ?: ensureRegistered()?.let { authToken } ?: return@withContext null
        val body = "{\"name\":\"${name.replace("\"", "")}\"}"
        runCatching {
            buildClient().newCall(
                Request.Builder()
                    .url(_networkConfig.value.relayUrl.trimEnd('/') + "/api/groups")
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toRequestBody())
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val text = response.body?.string().orEmpty()
                val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
                    ?: return@withContext null
                id to name
            }
        }.getOrNull()
    }

    /**
     * Redeems an invite to join [groupId] as this device on the relay.
     *
     * Server-side membership is what authorises every later publish and receive: without
     * it the relay answers 403 to each ciphertext. Returns true only when the server
     * confirmed membership.
     */
    suspend fun joinGroup(groupId: String, inviteToken: String): Boolean =
        withContext(Dispatchers.IO) {
            val token = authToken ?: ensureRegistered()?.let { authToken }
                ?: return@withContext false
            val body = "{\"token\":\"${escapeJson(inviteToken)}\"}"
            runCatching {
                buildClient().newCall(
                    Request.Builder()
                        .url(_networkConfig.value.relayUrl.trimEnd('/') + "/api/groups/$groupId/join")
                        .addHeader("Authorization", "Bearer $token")
                        .post(body.toRequestBody())
                        .build()
                ).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    /**
     * Removes this device's relay user from [groupId] (self-leave).
     *
     * Local deletion alone would leave the account authorised server-side, still
     * receiving ciphertext it can no longer decrypt. Returns true only when the server
     * confirmed the removal; if false the device remains a member until retried online.
     */
    suspend fun leaveGroup(groupId: String): Boolean = withContext(Dispatchers.IO) {
        val token = authToken ?: return@withContext false
        val userId = prefs.getString(KEY_USER_ID, null) ?: return@withContext false
        runCatching {
            buildClient().newCall(
                Request.Builder()
                    .url(
                        _networkConfig.value.relayUrl.trimEnd('/') +
                            "/api/groups/$groupId/members?userId=$userId"
                    )
                    .addHeader("Authorization", "Bearer $token")
                    .delete()
                    .build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Removes another member from [groupId]; only valid for the group owner, which the
     * server enforces. Returns true only when the server confirmed the removal.
     */
    suspend fun removeGroupMember(groupId: String, targetUserId: String): Boolean =
        withContext(Dispatchers.IO) {
            val token = authToken ?: return@withContext false
            runCatching {
                buildClient().newCall(
                    Request.Builder()
                        .url(
                            _networkConfig.value.relayUrl.trimEnd('/') +
                                "/api/groups/$groupId/members?userId=$targetUserId"
                        )
                        .addHeader("Authorization", "Bearer $token")
                        .delete()
                        .build()
                ).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    suspend fun createInviteToken(groupId: String): String? = withContext(Dispatchers.IO) {
        val token = authToken ?: return@withContext null
        runCatching {
            buildClient().newCall(
                Request.Builder()
                    .url(_networkConfig.value.relayUrl.trimEnd('/') + "/api/groups/$groupId/invites")
                    .addHeader("Authorization", "Bearer $token")
                    .post("{}".toRequestBody())
                    .build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                Regex("\"token\"\\s*:\\s*\"([^\"]+)\"")
                    .find(response.body?.string().orEmpty())?.groupValues?.getOrNull(1)
            }
        }.getOrNull()
    }

    /**
     * Publishes an already-encrypted location payload to a group.
     *
     * The ciphertext is produced by the caller; this method never handles plaintext.
     */
    suspend fun publishCiphertext(groupId: String, ciphertext: String): Boolean =
        withContext(Dispatchers.IO) {
            val token = authToken ?: return@withContext false
            runCatching {
                buildClient().newCall(
                    Request.Builder()
                        .url(_networkConfig.value.relayUrl.trimEnd('/') + "/api/groups/$groupId/messages")
                        .addHeader("Authorization", "Bearer $token")
                        .post("{\"ciphertext\":\"$ciphertext\"}".toRequestBody())
                        .build()
                ).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

    // ------------------------------------------------------------- item finding

    /**
     * Reports a BLE sighting of someone's lost item to the relay.
     *
     * The helper knows nothing about the item — only the rotating id it saw. The report
     * is authenticated so abuse can be traced, but the position is bound to a rotating
     * pseudonym the relay cannot link across hours.
     */
    suspend fun reportSighting(
        reportId: String,
        rotatingBleIdHex: String,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        rssiDbm: Int,
        timestampMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        val token = authToken ?: return@withContext false
        val body = buildString {
            append("{")
            append("\"reportId\":\"").append(escapeJson(reportId)).append("\",")
            append("\"rotatingBleId\":\"").append(escapeJson(rotatingBleIdHex)).append("\",")
            append("\"latitude\":").append(latitude).append(",")
            append("\"longitude\":").append(longitude).append(",")
            append("\"accuracy\":").append(accuracyMeters).append(",")
            append("\"rssi\":").append(rssiDbm).append(",")
            append("\"payload\":\"\",")
            append("\"authTag\":\"\",")
            append("\"timestamp\":").append(timestampMs)
            append("}")
        }
        runCatching {
            buildClient().newCall(
                Request.Builder()
                    .url(_networkConfig.value.relayUrl.trimEnd('/') + "/api/sightings")
                    .addHeader("Authorization", "Bearer $token")
                    .post(body.toRequestBody())
                    .build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * Asks the relay for the most recent sighting of [rotatingBleIdHex].
     *
     * Returns null when there is none (404), the request fails, or the answer does not
     * parse — the caller cannot distinguish those, which is fine: all mean "no usable
     * position right now".
     */
    suspend fun latestSighting(rotatingBleIdHex: String): LatestSighting? =
        withContext(Dispatchers.IO) {
            val token = authToken ?: return@withContext null
            runCatching {
                buildClient().newCall(
                    Request.Builder()
                        .url(
                            _networkConfig.value.relayUrl.trimEnd('/') +
                                "/api/items/$rotatingBleIdHex/sighting"
                        )
                        .addHeader("Authorization", "Bearer $token")
                        .get()
                        .build()
                ).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val answer = this@RelayClient.json
                        .parseToJsonElement(response.body?.string().orEmpty())
                        .jsonObject
                    val lat = answer["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@use null
                    val lon = answer["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@use null
                    LatestSighting(
                        latitude = lat,
                        longitude = lon,
                        accuracyMeters = answer["accuracy"]?.jsonPrimitive?.floatOrNull ?: 999f,
                        rssiDbm = answer["rssi"]?.jsonPrimitive?.intOrNull ?: -127,
                        timestampMs = answer["timestamp"]?.jsonPrimitive?.longOrNull ?: 0L
                    )
                }
            }.getOrNull()
        }

    // -------------------------------------------------------------- websocket

    /**
     * Opens the live socket and keeps it up with capped exponential backoff.
     *
     * Reconnect cadence backs off so a server outage does not drain the battery. The loop
     * lives in a cancellable job so [stopLiveWebSocket] can end it for good — an emergency
     * "stop sharing" must actually go quiet, not reconnect after a backoff.
     */
    fun startLiveWebSocket() {
        if (wsJob?.isActive == true) return
        wsJob = scope.launch {
            var backoffMs = INITIAL_BACKOFF_MS
            while (isActive) {
                val connectedThisTime = openSocket()
                if (connectedThisTime) {
                    backoffMs = INITIAL_BACKOFF_MS
                    // Hold the socket; the listener handles reconnect by failing.
                    while (isActive && _connected.value) delay(SOCKET_POLL_MS)
                }
                if (!isActive) break
                disconnect()
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /** Ends the reconnect loop and closes the socket. Nothing is transmitted afterwards. */
    fun stopLiveWebSocket() {
        wsJob?.cancel()
        wsJob = null
        disconnect()
    }

    private suspend fun openSocket(): Boolean {
        val token = authToken ?: return false
        val base = _networkConfig.value.relayUrl.trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val request = Request.Builder()
            .url("$base/ws?token=$token")
            .build()
        return suspendCancellableSocket(request)
    }

    private suspend fun suspendCancellableSocket(request: Request): Boolean =
        withContext(Dispatchers.IO) {
            var opened = false
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    opened = true
                    _connected.value = true
                    _lastError.value = null
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleInbound(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    _connected.value = false
                    _lastError.value = t.message
                    Log.w(TAG, "relay socket failed: ${t.message}")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _connected.value = false
                }
            }
            webSocket = buildClient().newWebSocket(request, listener)
            // Give the handshake a moment; onFailure/onOpen flips these flags.
            var waited = 0L
            while (!opened && waited < SOCKET_HANDSHAKE_TIMEOUT_MS && _lastError.value == null) {
                delay(SOCKET_POLL_MS)
                waited += SOCKET_POLL_MS
            }
            opened
        }

    private fun handleInbound(text: String) {
        // Inbound frames carry ciphertext only. Decryption and validation are the
        // repository's job, so nothing plaintext is ever constructed in this layer.
        scope.launch { decodeInboundFrame(text) }
        Log.d(TAG, "relay frame received (${text.length} bytes)")
    }

    fun disconnect() {
        runCatching { webSocket?.close(NORMAL_CLOSURE, "client closing") }
        webSocket = null
        _connected.value = false
    }

    /** Human-readable summary of where traffic is going. Never guesses. */
    fun connectivitySummary(): String {
        val config = _networkConfig.value
        return buildString {
            append("Relay: ").append(config.relayUrl).append('\n')
            append("Route: ").append(currentProxySummary()).append('\n')
            append("Socket: ").append(if (_connected.value) "connected" else "disconnected")
            _lastError.value?.let { append("\nLast error: ").append(it) }
        }
    }


    // ---------------------------------------------------------------- inbound

    /** Decrypted inbound location, emitted for the repository to apply. */
    private suspend fun decodeInboundFrame(text: String) {
        val ciphertext = Regex("\"ciphertext\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
            ?: return
        val groupId = Regex("\"groupId\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.getOrNull(1)
        val senderId = Regex("\"senderId\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
            ?: return
        val sequence = Regex("\"seq\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return
        inboundCiphertext.tryEmit(InboundCiphertext(senderId, sequence, groupId, ciphertext))
    }

    data class InboundCiphertext(
        val senderId: String,
        val sequence: Long,
        val groupId: String?,
        val ciphertext: String
   )

    private val inboundCiphertext =
        MutableSharedFlow<InboundCiphertext>(extraBufferCapacity = 128)

    /** Ciphertext frames awaiting decryption by the repository. */
    val inboundCiphertextFrames: SharedFlow<InboundCiphertext> = inboundCiphertext.asSharedFlow()

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val TAG = "KinNavRelay"
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
        private const val CALL_TIMEOUT_SECONDS = 45L
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val SOCKET_POLL_MS = 1_000L
        private const val SOCKET_HANDSHAKE_TIMEOUT_MS = 10_000L
        private const val NORMAL_CLOSURE = 1000
        private const val TOR_HOST = "127.0.0.1"
        private const val TOR_PORT = 9050

        const val KEY_RELAY_URL = "net.relay.url"
        const val KEY_USE_TOR = "net.relay.tor"
        const val KEY_SOCKS5 = "net.relay.socks5"
        const val KEY_SOCKS5_HOST = "net.relay.socks5.host"
        const val KEY_SOCKS5_PORT = "net.relay.socks5.port"
        const val KEY_RETENTION_DAYS = "net.relay.retention"
        const val KEY_AUTH_TOKEN = "net.auth.token"
        const val KEY_USER_ID = "net.user.id"
        const val KEY_DISPLAY_NAME = "net.display.name"
        const val KEY_DISPLAY_COLOR = "net.display.color"
    }
}