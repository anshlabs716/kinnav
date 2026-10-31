package org.geonav.relay

import org.geonav.relay.core.KotlinRelayServerEngine
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Standalone Luogo relay.
 *
 * Zero-knowledge by design. The server stores and forwards only ciphertext that clients
 * produced; it holds no group keys, so a compromise does not reveal anyone's location.
 * Authorisation (is the sender in this group, is this invite still valid) is enforced here.
 * Confidentiality is the clients' job.
 *
 * All authorisation decisions delegate to [KotlinRelayServerEngine], which the Android app
 * also embeds and which is covered by unit tests, so the two cannot drift apart.
 *
 * Run with `-addr :8080 -max-log 500`. TLS is expected to terminate in front of this, either
 * at a reverse proxy or a tunnel, because bearer tokens travel in every request.
 */
object RelayServer {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val random = SecureRandom()


    @JvmStatic
    fun main(args: Array<String>) {
        var port = 8080
        var maxLog = 500
        var host = ""

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-addr" -> {
                    val raw = args.getOrNull(++i) ?: ":8080"
                    host = raw.substringBeforeLast(':', "")
                    port = raw.substringAfterLast(':').toIntOrNull() ?: 8080
                }
                "-max-log" -> maxLog = (args.getOrNull(++i))?.toIntOrNull() ?: 500
                "-help", "--help" -> {
                    println("usage: -addr [host:]port -max-log <n>")
                    return
                }
            }
            i++
        }

        val engine = KotlinRelayServerEngine(maxLogPerGroup = maxLog)
        val server = HttpServer.create(InetSocketAddress(if (host.isBlank()) port else port), 0)
        server.createContext("/") { exchange -> Handler(engine).handle(exchange) }
        // Virtual threads: a WebSocket connection blocks a thread for its lifetime, so this
        // matters more here than for a typical request/response server.
        server.executor = Executors.newVirtualThreadPerTaskExecutor()

        println("Luogo relay listening on :$port (maxLog=$maxLog, runtime=kotlin)")
        server.start()
    }

    private class Handler(private val engine: KotlinRelayServerEngine) {

        fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path.orEmpty()
            val method = exchange.requestMethod.uppercase()
            val query = parseQuery(exchange.requestURI.rawQuery)
            var upgraded = false
            try {
                when {
                    // ---------------------------------------------------- unauthenticated
                    path == "/api/health" && method == "GET" ->
                        respond(exchange, 200, buildJsonObject {
                            put("ok", true)
                            put("version", PROTOCOL_VERSION)
                            put("runtime", "kotlin")
                        })

                    path == "/api/users" && method == "POST" ->
                        createUser(exchange)

                    path == "/ws" && method == "GET" ->
                        upgraded = upgradeToWebSocket(exchange, query)

                    // ------------------------------------------------------ authenticated
                    else -> {
                        val token = exchange.requestHeaders.getFirst("Authorization")
                        val user = engine.authenticateToken(token)
                            ?: return respondError(exchange, 401, "unauthorized")
                        route(exchange, engine, user, path, method, query)
                    }
                }
            } catch (e: Exception) {
                // Logged locally for the operator; the client only ever learns that the
                // request failed, never why.
                System.err.println("relay error on ${method} $path: ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
                if (!upgraded) respondError(exchange, 500, "internal error")
            } finally {
                // A WebSocket connection outlives this call and is closed by its pump thread.
                if (!upgraded) exchange.close()
            }
        }

        private fun route(
            exchange: HttpExchange,
            engine: KotlinRelayServerEngine,
            user: KotlinRelayServerEngine.User,
            path: String,
            method: String,
            query: Map<String, String>
        ) {
            val parts = path.trim('/').split('/')

            when {
                parts == listOf("api", "groups") && method == "GET" ->
                    respond(exchange, 200, buildJsonObject {
                        put("groups", buildJsonArray {
                            engine.groupsForUser(user.id).forEach { group ->
                                add(buildJsonObject {
                                    put("id", group.id)
                                    put("name", group.name)
                                    put("createdAt", group.createdAt)
                                    put("memberCount", engine.memberCountOf(group.id))
                                })
                            }
                        })
                    })

                parts == listOf("api", "groups") && method == "POST" -> {
                    val body = readJsonObject(exchange)
                    val name = body["name"]?.jsonPrimitive?.contentOrNullSafe()?.trim()
                        ?.takeIf { it.isNotEmpty() } ?: "Group"
                    val group = engine.createGroup(user.id, name)
                    respond(exchange, 201, buildJsonObject {
                        put("id", group.id)
                        put("name", group.name)
                        put("createdAt", group.createdAt)
                    })
                }

                parts.size == 4 && parts[0] == "api" && parts[1] == "groups" && parts[3] == "invites" && method == "POST" -> {
                    val invite = engine.createInvite(parts[2], user.id)
                        ?: return respondError(exchange, 403, "not a member of this group")
                    respond(exchange, 201, buildJsonObject {
                        put("token", invite.token)
                        put("groupId", invite.groupId)
                        put("expiresAt", invite.expiresAt)
                    })
                }

                parts.size == 4 && parts[0] == "api" && parts[1] == "groups" && parts[3] == "join" && method == "POST" -> {
                    val body = readJsonObject(exchange)
                    val token = body["token"]?.jsonPrimitive?.contentOrNullSafe()
                        ?: return respondError(exchange, 400, "token required")
                    val joined = engine.joinGroupWithInvite(parts[2], user.id, token)
                        ?: return respondError(exchange, 403, "invite invalid, expired, or already used")
                    respond(exchange, 200, buildJsonObject {
                        put("id", joined.id)
                        put("name", joined.name)
                    })
                }

                parts.size == 4 && parts[0] == "api" && parts[1] == "groups" && parts[3] == "messages" && method == "POST" -> {
                    val body = readJsonObject(exchange)
                    val ciphertext = body["ciphertext"]?.jsonPrimitive?.contentOrNullSafe()
                        ?: return respondError(exchange, 400, "ciphertext required")
                    val stored = engine.appendMessage(parts[2], user.id, ciphertext)
                        ?: return respondError(exchange, 403, "not a member of this group")
                    respond(exchange, 201, buildJsonObject {
                        put("seq", stored.seq)
                    })
                }

                parts.size == 4 && parts[0] == "api" && parts[1] == "groups" && parts[3] == "messages" && method == "GET" -> {
                    val after = query["after"]?.toLongOrNull() ?: 0L
                    val messages = engine.getMessagesAfter(parts[2], user.id, after)
                        ?: return respondError(exchange, 403, "not a member of this group")
                    respond(exchange, 200, buildJsonObject {
                        put("messages", buildJsonArray {
                            messages.forEach { message ->
                                add(buildJsonObject {
                                    put("seq", message.seq)
                                    put("senderId", message.senderId)
                                    put("timestamp", message.timestamp)
                                    put("ciphertext", message.ciphertext)
                                })
                            }
                        })
                    })
                }

                parts.size == 4 && parts[0] == "api" && parts[1] == "groups" && parts[3] == "members" && method == "DELETE" -> {
                    val target = query["userId"]
                        ?: return respondError(exchange, 400, "userId required")
                    val removed = engine.removeMember(parts[2], user.id, target)
                    respond(
                        exchange,
                        if (removed) 200 else 403,
                        buildJsonObject { put("removed", removed) }
                    )
                }

                parts == listOf("api", "sightings") && method == "POST" -> {
                    val body = readJsonObject(exchange)
                    val sighting = KotlinRelayServerEngine.StoredSighting(
                        reportId = body["reportId"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                        rotatingBleIdHex = body["rotatingBleId"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                        helperLatitude = body["latitude"]?.jsonPrimitive?.contentOrNullSafe()?.toDoubleOrNull() ?: 0.0,
                        helperLongitude = body["longitude"]?.jsonPrimitive?.contentOrNullSafe()?.toDoubleOrNull() ?: 0.0,
                        helperAccuracyMeters = body["accuracy"]?.jsonPrimitive?.contentOrNullSafe()?.toFloatOrNull() ?: 999f,
                        rssiDbm = body["rssi"]?.jsonPrimitive?.contentOrNullSafe()?.toIntOrNull() ?: -127,
                        encryptedPayload = body["payload"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                        authTag = body["authTag"]?.jsonPrimitive?.contentOrNullSafe().orEmpty(),
                        timestamp = body["timestamp"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull() ?: 0L
                    )
                    // Duplicate report ids are rejected, so a retrying helper cannot inflate
                    // someone's sighting count.
                    val accepted = engine.recordSighting(sighting)
                    respond(exchange, if (accepted) 201 else 409, buildJsonObject {
                        put("accepted", accepted)
                    })
                }

                parts.size == 4 && parts[0] == "api" && parts[1] == "items" && parts[3] == "sighting" && method == "GET" -> {
                    // Only an authenticated request may ask. The payload stays ciphertext:
                    // the owner decrypts it with the item key, which the server never holds.
                    // Note this authenticates the *requester*, not the ownership of the item;
                    // the owner's client verifies the tag before trusting a position.
                    val latest = engine.latestSightingForItem(parts[2])
                        ?: return respondError(exchange, 404, "no sighting for that item")
                    respond(exchange, 200, buildJsonObject {
                        put("reportId", latest.reportId)
                        put("latitude", latest.helperLatitude)
                        put("longitude", latest.helperLongitude)
                        put("accuracy", latest.helperAccuracyMeters)
                        put("rssi", latest.rssiDbm)
                        put("timestamp", latest.timestamp)
                        put("payload", latest.encryptedPayload)
                        put("authTag", latest.authTag)
                    })
                }

                else -> respondError(exchange, 404, "not found")
            }
        }

        private fun createUser(exchange: HttpExchange) {
            val body = readJsonObject(exchange)
            val name = body["name"]?.jsonPrimitive?.contentOrNullSafe()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: "Luogo user"
            val color = body["color"]?.jsonPrimitive?.contentOrNullSafe()?.toLongOrNull()
                ?: 0xFF006C4CL
            val publicKey = body["publicKey"]?.jsonPrimitive?.contentOrNullSafe().orEmpty()
            val (user, token) = engine.createUser(name, color, publicKey)
            respond(exchange, 201, buildJsonObject {
                put("user", buildJsonObject {
                    put("id", user.id)
                    put("name", user.name)
                    put("color", user.color)
                    put("publicKey", user.publicKey)
                })
                put("token", token)
            })
        }

        /**
         * Performs the RFC 6455 handshake and starts a listener thread for the socket.
         *
         * `com.sun.net.httpserver` has no WebSocket support, so the upgrade is done by hand.
         * The accept token is the SHA-1 of the client key plus the magic GUID, per the spec,
         * which stops a third party from replaying a captured handshake.
         */
        /** Returns true once the exchange has become a WebSocket the pump thread owns. */
        private fun upgradeToWebSocket(exchange: HttpExchange, query: Map<String, String>): Boolean {
            val key = exchange.requestHeaders.getFirst("Sec-WebSocket-Key")
            if (key.isNullOrBlank()) {
                respondError(exchange, 400, "missing Sec-WebSocket-Key")
                return false
            }
            if (exchange.requestHeaders.getFirst("Upgrade")?.contains("websocket", true) != true) {
                respondError(exchange, 400, "not a websocket upgrade")
                return false
            }
            val token = query["token"].orEmpty()
            val user = engine.authenticateToken(token)
            if (user == null) {
                respondError(exchange, 401, "unauthorized")
                return false
            }

            val accept = sha1Base64(
                key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
            )
            exchange.responseHeaders.add("Upgrade", "websocket")
            exchange.responseHeaders.add("Connection", "Upgrade")
            exchange.responseHeaders.add("Sec-WebSocket-Accept", accept)
            exchange.sendResponseHeaders(101, 0)

            val input = exchange.requestBody
            val output = exchange.responseBody

            val session = engine.connectWebSocket(user.id) { payload ->
                runCatching { writeTextFrame(output, payload) }
            }

            // The pump owns the connection from here. handle() is told not to close the
            // exchange, because closing it would terminate the socket we just upgraded.
            Thread {
                try {
                    readFramesForever(input)
                } catch (_: Exception) {
                    // Client closed or the connection dropped; fall through to cleanup.
                } finally {
                    session.close()
                    runCatching { output.close() }
                    runCatching { exchange.close() }
                }
            }.apply { isDaemon = true }.start()
            return true
        }

        /**
         * Drains client frames so the peer's send buffer never fills and stalls our writes.
         *
         * Payloads are discarded: clients publish over HTTP, so nothing arriving on the
         * socket is trusted or acted upon. Frames are still parsed to their exact length,
         * because skipping a variable-length payload would desynchronise every frame after it.
         */
        private fun readFramesForever(input: InputStream) {
            while (true) {
                val first = input.read()
                if (first < 0) return
                val second = input.read()
                if (second < 0) return

                val opcode = first and 0x0F
                val masked = (second and 0x80) != 0
                var length = (second and 0x7F).toLong()

                if (length == 126L) {
                    length = ((input.read() shl 8) or input.read()).toLong()
                } else if (length == 127L) {
                    var value = 0L
                    repeat(8) { value = (value shl 8) or input.read().toLong() }
                    length = value
                }

                val mask = if (masked) ByteArray(4) { input.read().toByte() } else null

                // Refuse to buffer an absurd frame rather than allocating whatever a client
                // claims to be sending.
                if (length > MAX_CLIENT_FRAME_BYTES) return

                val payload = ByteArray(length.toInt())
                var read = 0
                while (read < payload.size) {
                    val chunk = input.read(payload, read, payload.size - read)
                    if (chunk < 0) return
                    read += chunk
                }
                if (mask != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }
                if (opcode == OPCODE_CLOSE) return
            }
        }

        private fun writeTextFrame(output: OutputStream, text: String) {
            // Server-to-client frames are never masked, per RFC 6455.
            val payload = text.toByteArray(Charsets.UTF_8)
            val header = ByteArrayOutputStreamLite()
            header.write(0x81) // FIN + text frame
            when {
                payload.size < 126 -> header.write(payload.size)
                payload.size <= 0xFFFF -> {
                    header.write(126)
                    header.write((payload.size shr 8) and 0xFF)
                    header.write(payload.size and 0xFF)
                }
                else -> {
                    header.write(127)
                    for (shift in 56 downTo 0 step 8) {
                        header.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                    }
                }
            }
            output.write(header.toByteArray())
            output.write(payload)
            output.flush()
        }
    }

    // ------------------------------------------------------------------ helpers

    private const val PROTOCOL_VERSION = 1

    private const val OPCODE_CLOSE = 0x8

    /** 1 MiB. Anything larger is a client bug or an attempt to exhaust memory. */
    private const val MAX_CLIENT_FRAME_BYTES = 1L shl 20

    private fun sha1Base64(value: String): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.US_ASCII))
        )


    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index <= 0) null else {
                val key = pair.substring(0, index)
                val value = pair.substring(index + 1)
                java.net.URLDecoder.decode(key, "UTF-8") to
                    java.net.URLDecoder.decode(value, "UTF-8")
            }
        }.toMap()
    }

    private fun readJsonObject(exchange: HttpExchange): JsonObject {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        if (body.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(body).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }

    private fun respond(exchange: HttpExchange, status: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondError(exchange: HttpExchange, status: Int, message: String) {
        respond(exchange, status, buildJsonObject { put("error", message) })
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()

    /** Minimal growable byte buffer; avoids pulling in java.io.ByteArrayOutputStream naming. */
    private class ByteArrayOutputStreamLite {
        private var buffer = ByteArray(64)
        private var size = 0

        fun write(value: Int) {
            if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[size++] = value.toByte()
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}

/**
 * Entry point for `java -jar`, so the main class is stable regardless of the object name.
 */
fun main(args: Array<String>) {
    RelayServer.main(args)
}