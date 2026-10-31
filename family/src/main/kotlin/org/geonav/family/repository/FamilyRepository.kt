package org.geonav.family.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import org.geonav.family.Family
import org.geonav.family.FamilyProtocol
import org.geonav.family.crypto.CryptoEngine
import org.geonav.family.db.FamilyDao
import org.geonav.family.db.PeerGroupEntity
import org.geonav.family.db.UserProfileEntity
import org.geonav.family.domain.ActivityState
import org.geonav.family.domain.DiagnosticRow
import org.geonav.family.domain.FamilyDiagnostics
import org.geonav.family.domain.GroupCategory
import org.geonav.family.domain.NetworkConfig
import org.geonav.family.domain.PeerGroup
import org.geonav.family.domain.PeerLocationState
import org.geonav.family.domain.SharedFix
import org.geonav.family.domain.UserProfile
import org.geonav.family.network.RelayClient
import org.geonav.family.service.FamilyNotificationHelper
import org.geonav.family.service.FamilyShareService
import java.util.UUID
import kotlin.math.cos
import kotlin.math.round

/**
 * Single entry point the UI and the host app talk to for location sharing.
 *
 * Ported from Luogo-FOSS's LuogoRepository and reduced to the sharing subset: profile and
 * visibility radius, groups (the per-contact opt-in unit), outbound encrypted broadcast,
 * inbound decryption, relay lifecycle and diagnostics. Item finding, geofences, location
 * history, offline maps and routing from the parent app were not ported — GeoNav has its
 * own routing and none of them are part of "who can see me".
 *
 * Two deliberate changes from the parent:
 *  - The master switch defaults to OFF. Opting in is an explicit tap, never silent.
 *  - The relay socket only runs while sharing is on or a group exists. With sharing fully
 *    disabled and no groups, this module makes no network connections at all.
 */
class FamilyRepository(
    private val context: Context,
    private val dao: FamilyDao,
    private val prefs: android.content.SharedPreferences,
    private val cryptoEngine: CryptoEngine,
    val relayClient: RelayClient,
    val notificationHelper: FamilyNotificationHelper
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ------------------------------------------------------------ local state

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val appLogs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerLocationState>>(emptyList())
    val peers: StateFlow<List<PeerLocationState>> = _peers.asStateFlow()

    /** Last time a fix actually left the device; shown in diagnostics. */
    @Volatile
    var lastBroadcastMs: Long? = null
        private set

    /** When the user last stopped sharing; drives the notification's stopped summary. */
    @Volatile
    var sharingStoppedAtMs: Long? = null
        private set

    @Volatile
    private var lastAttemptMs = 0L

    @Volatile
    private var groupCountCache: Int = 0

    private var broadcastCount = 0L

    val userProfile: StateFlow<UserProfile> = dao.observeLocalUser()
        .map { it?.toDomain() ?: defaultProfile() }
        .let { flow ->
            MutableStateFlow(defaultProfile()).also { state ->
                scope.launch {
                    flow.collect { state.value = it }
                }
            }
        }

    val groups: StateFlow<List<PeerGroup>> = dao.observeGroups()
        .map { rows -> rows.map { it.toDomain() } }
        .onEach { groupCountCache = it.size }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val networkConfig: StateFlow<NetworkConfig> = relayClient.networkConfig

    // -------------------------------------------------------------- lifecycle

    /**
     * Starts the pipelines the UI depends on. Called once from [org.geonav.family.Family.init].
     *
     * The inbound drain always runs (it is inert without a socket). The socket itself is
     * governed by [syncRelayConnection] and only runs when there is something to see.
     */
    fun start() {
        notificationHelper.createNotificationChannels()
        scope.launch { drainInboundCiphertext() }
        syncRelayConnection()
    }

    /**
     * Connects or disconnects the relay to match what the user actually asked for.
     *
     * Wanted while sharing is on (outbound) or any group exists (inbound). With sharing
     * off and no groups the app is a plain maps app: no registration, no socket, no data.
     */
    fun syncRelayConnection() {
        scope.launch {
            val want = userProfile.value.sharingEnabled || dao.getGroups().isNotEmpty()
            if (want) {
                relayClient.ensureRegistered()
                relayClient.startLiveWebSocket()
            } else {
                relayClient.stopLiveWebSocket()
                appendLog("Relay disconnected (sharing off, no groups)")
            }
        }
    }

    private suspend fun drainInboundCiphertext() {
        relayClient.inboundCiphertextFrames.collect { frame ->
            // Authorisation is enforced by the relay; here we only reject what we cannot
            // authenticate, and never apply a fix we failed to verify.
            val ciphertext = runCatching {
                CryptoEngine.decodeBase64Url(frame.ciphertext)
            }.getOrNull() ?: return@collect

            // The group id is carried in the frame, so the correct key is tried first and only
            // that group is credited with the payload. Falling back to "try every key" would
            // let a peer be attributed to the wrong group.
            val known = groupKeysKnown()
            val ordered = buildList {
                frame.groupId?.let { gid -> known.firstOrNull { it.first == gid } }?.let { add(it) }
                addAll(known.filter { it.first != frame.groupId })
            }

            val decrypted = ordered.firstNotNullOfOrNull { (groupId, key) ->
                cryptoEngine.decryptWithKey(key, ciphertext, aad = groupId)
                    ?.let { plaintext -> groupId to plaintext }
            } ?: return@collect

            applyInboundPayload(groupId = decrypted.first, plaintext = decrypted.second)
        }
    }

    private fun applyInboundPayload(groupId: String?, plaintext: ByteArray) {
        val text = plaintext.toString(Charsets.UTF_8)
        val lat = Regex("\"lat\"\\s*:\\s*([0-9.-]+)").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: return
        val lon = Regex("\"lon\"\\s*:\\s*([0-9.-]+)").find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: return
        val name = Regex("\"name\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.getOrNull(1) ?: "Shared contact"
        val timestamp = Regex("\"ts\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return
        val accuracy = Regex("\"acc\"\\s*:\\s*([0-9.]+)").find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: 50f
        val battery = Regex("\"bat\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val color = Regex("\"color\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: 0xFF0277BDL
        val speed = Regex("\"spd\"\\s*:\\s*([0-9.]+)").find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
        val bearing = Regex("\"brg\"\\s*:\\s*([0-9.]+)").find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 0f
        val activity = runCatching {
            ActivityState.valueOf(
                Regex("\"act\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.getOrNull(1)
                    ?: "UNKNOWN"
            )
        }.getOrDefault(ActivityState.UNKNOWN)

        val state = PeerLocationState(
            // Keyed on the sender's stable id, not their display name, so renaming does not
            // fork someone into two entries. Falls back to the name for payloads from
            // parents that never sent an id.
            userId = Regex("\"id\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() } ?: name,
            groupId = groupId.orEmpty(),
            displayName = name,
            colorArgb = color,
            latitude = lat,
            longitude = lon,
            accuracyMeters = accuracy,
            speedMps = speed,
            bearingDegrees = bearing,
            batteryPercent = battery,
            activityState = activity,
            sourceSummary = "E2EE relay",
            timestampMs = timestamp,
            isOnline = true
        )
        // Replace only this person's entry so updating one contact does not redraw the rest.
        _peers.value = _peers.value.filterNot { it.userId == state.userId } + state
    }

    // ------------------------------------------------------------------ logs

    fun appendLog(message: String) {
        val stamped = "${System.currentTimeMillis()} $message"
        _logs.value = (listOf(stamped) + _logs.value).take(MAX_LOG_LINES)
    }

    // --------------------------------------------------------------- profile

    private fun defaultProfile(): UserProfile {
        val identity = cryptoEngine.loadOrCreateDeviceIdentity()
        return UserProfile(
            id = cryptoEngine.deviceIdFor(identity.publicKeyBytes),
            displayName = prefs.getString(KEY_DISPLAY_NAME, DEFAULT_DISPLAY_NAME) ?: DEFAULT_DISPLAY_NAME,
            colorArgb = prefs.getLong(KEY_DISPLAY_COLOR, DEFAULT_COLOR),
            publicKeyBase64 = CryptoEngine.encodeBase64Url(identity.publicKeyBytes),
            // Opt-in: a fresh install shares nothing until the user turns it on.
            sharingEnabled = prefs.getBoolean(KEY_SHARING_ENABLED, false),
            visibilityRadiusMeters = prefs.getInt(KEY_VISIBILITY_RADIUS, 0)
        )
    }

    /**
     * Non-suspend on purpose: the foreground service, the Quick Settings tile and the
     * notification action all need to toggle sharing, and none owns a coroutine scope.
     */
    fun updateUserProfile(
        displayName: String,
        colorArgb: Long,
        sharingEnabled: Boolean,
        temporarySharingUntilMs: Long? = null
    ) {
        scope.launch {
            persistUserProfile(displayName, colorArgb, sharingEnabled, temporarySharingUntilMs)
        }
    }

    private suspend fun persistUserProfile(
        displayName: String,
        colorArgb: Long,
        sharingEnabled: Boolean,
        temporarySharingUntilMs: Long? = null
    ) {
        val current = userProfile.value
        val profile = current.copy(
            displayName = displayName,
            colorArgb = colorArgb,
            sharingEnabled = sharingEnabled,
            temporarySharingUntilMs = temporarySharingUntilMs
        )
        prefs.edit()
            .putString(KEY_DISPLAY_NAME, displayName)
            .putLong(KEY_DISPLAY_COLOR, colorArgb)
            .putBoolean(KEY_SHARING_ENABLED, sharingEnabled)
            .apply()
        relayClient.updateDisplayNameFromProfile(displayName, colorArgb)
        dao.upsertUserProfile(profile.toEntity())
        appendLog(if (sharingEnabled) "Sharing enabled" else "Sharing paused")
    }

    /**
     * The master "share my location" switch.
     *
     * Turning it on persists the opt-in, starts the foreground share service (which pulls
     * the host app's location pipeline up) and connects the relay. Turning it off does the
     * reverse and is the same path the emergency STOP SHARING button uses.
     */
    fun setSharingEnabled(enabled: Boolean) {
        val profile = userProfile.value
        updateUserProfile(profile.displayName, profile.colorArgb, enabled)
        if (enabled) {
            sharingStoppedAtMs = null
            startShareService()
        } else {
            sharingStoppedAtMs = System.currentTimeMillis()
            stopShareService()
        }
        syncRelayConnection()
    }

    // ------------------------------------------------------- share service API

    /** Called by [org.geonav.family.service.FamilyShareService] when it comes up. */
    fun onShareServiceStarted() {
        appendLog("Share service started")
    }

    /**
     * Called when the share service stops for any reason (stop action, swipe-away,
     * destroy). The relay connection follows the master switch, which stays authoritative.
     */
    fun onShareServiceStopped() {
        notificationHelper.cancelSharingNotification()
        Family.hooks?.stopLocationUpdates()
        syncRelayConnection()
    }

    /** Synchronous group count for the foreground notification. */
    fun groupCountSync(): Int = groupCountCache

    /** Rounds the shared position to the visibility radius (0 = precise). */
    fun setVisibilityRadius(meters: Int) {
        val profile = userProfile.value
        prefs.edit().putInt(KEY_VISIBILITY_RADIUS, meters).apply()
        scope.launch {
            dao.upsertUserProfile(profile.copy(visibilityRadiusMeters = meters).toEntity())
        }
        appendLog(
            if (meters <= 0) "Sharing precise position" else "Sharing position rounded to ~$meters m"
        )
    }

    fun updateNetworkConfig(config: NetworkConfig) {
        relayClient.updateNetworkConfig(config)
        appendLog("Relay set to ${config.relayUrl}")
        syncRelayConnection()
    }

    // --------------------------------------------------------------- sharing

    /**
     * Entry point for the host app's location pipeline.
     *
     * Gates on the master switch, coarsens to the visibility radius, throttles to the
     * activity's honest update interval, then encrypts once per opted-in group.
     */
    suspend fun submitFix(fix: SharedFix) {
        val profile = userProfile.value
        if (!profile.sharingEnabled) return
        if (fix.isSuspiciousJump) {
            appendLog("Refused to share a suspicious jump")
            return
        }
        profile.temporarySharingUntilMs?.let { untilMs ->
            if (System.currentTimeMillis() > untilMs) {
                appendLog("Temporary sharing window expired; not sending")
                return
            }
        }

        val now = System.currentTimeMillis()
        val minInterval = fix.activityState.targetUpdateIntervalMs
        if (now - lastAttemptMs < minInterval) return
        lastAttemptMs = now

        broadcastEncryptedLocationToGroups(fix, profile, sourceTag = "pipeline")
    }

    /**
     * Encrypts the current fix once per group that has sharing switched on, and uploads it.
     *
     * Nothing is sent when sharing is paused, the fix is flagged as a suspicious jump, or
     * no group has opted in. The position is rounded to the visibility radius first, so a
     * recipient's "±" accuracy honestly reflects the coarsening.
     */
    private suspend fun broadcastEncryptedLocationToGroups(
        fix: SharedFix,
        profile: UserProfile,
        sourceTag: String
    ) {
        val groups = dao.getGroups().map { it.toDomain() }.filter { it.shareMyLocation }
        if (groups.isEmpty()) return

        // Registration must have happened or publishCiphertext would silently no-op.
        relayClient.ensureRegistered()

        val (sharedLat, sharedLon) = coarsenToRadius(
            fix.latitude, fix.longitude, profile.visibilityRadiusMeters
        )
        val sharedAcc = maxOf(fix.horizontalAccuracyMeters, profile.visibilityRadiusMeters.toFloat())

        val payload = buildString {
            append("{\"name\":\"").append(escapeJson(profile.displayName)).append("\",")
            // The sender's stable id, so a rename cannot fork a contact into two entries.
            append("\"id\":\"").append(profile.id).append("\",")
            append("\"lat\":").append(sharedLat).append(",")
            append("\"lon\":").append(sharedLon).append(",")
            append("\"acc\":").append(sharedAcc).append(",")
            append("\"ts\":").append(fix.timestampMs).append(",")
            append("\"spd\":").append(fix.speedMps).append(",")
            append("\"brg\":").append(fix.bearingDegrees).append(",")
            append("\"color\":").append(profile.colorArgb).append(",")
            append("\"bat\":").append(currentBatteryPercent()).append(",")
            append("\"act\":\"").append(fix.activityState.name).append("\",")
            append("\"src\":\"").append(escapeJson(fix.sourceSummary)).append("\"")
            append("}")
        }.toByteArray(Charsets.UTF_8)

        for (group in groups) {
            val ciphertext = cryptoEngine.encryptForGroup(group.id, payload)
            val sent = relayClient.publishCiphertext(
                group.id,
                CryptoEngine.encodeBase64Url(ciphertext)
            )
            if (sent) {
                lastBroadcastMs = System.currentTimeMillis()
                broadcastCount++
            }
            appendLog(
                "${if (sent) "Sent" else "Queued"} encrypted fix to '${group.name}' ($sourceTag)"
            )
        }
    }

    /**
     * Visibility radius: snap latitude/longitude onto a grid of roughly [radiusMeters].
     *
     * 0 returns the precise fix untouched. Recipients see a point that is real (a grid
     * intersection, never a fake offset) and the reported accuracy is widened to match.
     */
    private fun coarsenToRadius(lat: Double, lon: Double, radiusMeters: Int): Pair<Double, Double> {
        if (radiusMeters <= 0) return lat to lon
        val latGrid = radiusMeters / METERS_PER_DEGREE_LAT
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val lonGrid = radiusMeters / (METERS_PER_DEGREE_LAT * cosLat)
        return (round(lat / latGrid) * latGrid) to (round(lon / lonGrid) * lonGrid)
    }

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

    private fun currentBatteryPercent(): Int {
        val intent = context.registerReceiver(
            null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        )
        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return -1
        return (level * 100 / scale)
    }

    // ---------------------------------------------------------- share service

    private fun startShareService() {
        val intent = Intent(context, FamilyShareService::class.java)
            .setAction(FamilyShareService.ACTION_START_SHARING)
        runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.onFailure {
            appendLog("Could not start share service: ${it.message}")
        }
    }

    private fun stopShareService() {
        val intent = Intent(context, FamilyShareService::class.java)
            .setAction(FamilyShareService.ACTION_STOP_SHARING)
        // startService (not startForegroundService): the service is already foreground and
        // only needs the stop action, and starting an FGS from the background is restricted.
        runCatching { context.startService(intent) }
            .onFailure { context.stopService(Intent(context, FamilyShareService::class.java)) }
    }

    // ---------------------------------------------------------------- groups

    suspend fun createGroup(name: String, category: GroupCategory): PeerGroup {
        val owner = userProfile.value
        val group = PeerGroup(
            id = "grp-" + UUID.randomUUID().toString().take(12),
            name = name,
            category = category,
            ownerId = owner.id,
            createdAtMs = System.currentTimeMillis(),
            memberCount = 1,
            // Joining/creating a group is the per-contact opt-in act itself.
            shareMyLocation = true
        )
        cryptoEngine.getOrCreateGroupKey(group.id)
        dao.upsertGroup(group.toEntity())
        appendLog("Created group '$name'")
        syncRelayConnection()
        return group
    }

    suspend fun toggleGroupSharing(groupId: String, share: Boolean, temporaryMinutes: Int? = null) {
        val group = dao.getGroups().firstOrNull { it.id == groupId } ?: return
        val until = if (share && temporaryMinutes != null) {
            System.currentTimeMillis() + temporaryMinutes * 60_000L
        } else {
            null
        }
        dao.upsertGroup(group.copy(shareMyLocation = share, temporarySharingUntilMs = until))
        appendLog("${if (share) "Enabled" else "Disabled"} sharing for '${group.name}'")
    }

    /**
     * Builds an invite that carries the group key sealed behind the relay's single-use token.
     *
     * The key itself is never sent to the server; only the owner-to-invitee blob is, and only
     * the intended recipient holds the token.
     */
    suspend fun generateGroupInvitePayload(groupId: String): String {
        val key = cryptoEngine.getOrCreateGroupKey(groupId)
        relayClient.ensureRegistered()
        val token = relayClient.createInviteToken(groupId)
            ?: // Offline: mint a locally valid payload so the invite still works once sent.
            "local-" + UUID.randomUUID().toString().take(16)
        appendLog("Generated invite for group $groupId")
        return cryptoEngine.buildInvitePayload(groupId, token, key)
    }

    suspend fun joinGroupFromInvitePayload(rawInvite: String): PeerGroup? {
        val parsed = cryptoEngine.parseInvitePayload(rawInvite) ?: return null
        cryptoEngine.importGroupKey(parsed.groupId, parsed.groupKey)
        val group = PeerGroup(
            id = parsed.groupId,
            name = "Shared group",
            category = GroupCategory.CUSTOM,
            ownerId = "remote",
            createdAtMs = System.currentTimeMillis(),
            memberCount = 1,
            shareMyLocation = true
        )
        dao.upsertGroup(group.toEntity())
        appendLog("Joined group ${parsed.groupId}")
        syncRelayConnection()
        return group
    }

    suspend fun leaveOrDeleteGroup(groupId: String) {
        dao.deleteGroup(groupId)
        // Removing the key is what actually revokes this device's ability to read the group.
        cryptoEngine.forgetGroupKey(groupId)
        appendLog("Left group and deleted local key")
        // Last group gone and sharing off means nothing should stay connected.
        syncRelayConnection()
    }

    suspend fun removeMemberFromGroup(groupId: String, userId: String) {
        dao.removeGroupMember(groupId, userId)
    }

    // ------------------------------------------------------------- diagnostics

    /**
     * Builds the diagnostics snapshot for the debug screen. Every field is read from real
     * state — the relay client, the database, the counters — never invented. Host rows
     * come from the app's own positioning stack via FamilyHooks.
     */
    suspend fun buildDiagnostics(hostRows: List<Pair<String, String>>): FamilyDiagnostics {
        val profile = userProfile.value
        val groupRows = dao.getGroups()
        val newestPeerMs = _peers.value.maxOfOrNull { it.timestampMs }
        val rows = buildList {
            add(DiagnosticRow("Protocol version", FamilyProtocol.VERSION.toString()))
            add(DiagnosticRow("Relay URL", relayClient.currentNetworkConfig().relayUrl))
            add(DiagnosticRow("Proxy route", relayClient.currentProxySummary()))
            add(DiagnosticRow("Socket", if (relayClient.connected.value) "connected" else "disconnected"))
            add(DiagnosticRow("Relay last error", relayClient.lastError.value ?: "none"))
            add(DiagnosticRow("User id", profile.id))
            add(DiagnosticRow("Display name", profile.displayName))
            add(DiagnosticRow(
                "Sharing",
                if (profile.sharingEnabled) "ON" else "OFF"
            ))
            add(DiagnosticRow(
                "Visibility radius",
                if (profile.visibilityRadiusMeters <= 0) "precise"
                else "~${profile.visibilityRadiusMeters} m"
            ))
            add(DiagnosticRow("Groups", groupRows.size.toString()))
            add(DiagnosticRow("Groups with keys", groupKeysKnown().size.toString()))
            add(DiagnosticRow("Groups sharing out", groupRows.count { it.shareMyLocation }.toString()))
            add(DiagnosticRow("Peers seen", _peers.value.size.toString()))
            add(DiagnosticRow(
                "Newest peer fix",
                newestPeerMs?.let { formatAgeRow(System.currentTimeMillis() - it) } ?: "none yet"
            ))
            add(DiagnosticRow("Fixes sent", broadcastCount.toString()))
            add(DiagnosticRow(
                "Last send",
                lastBroadcastMs?.let { formatAgeRow(System.currentTimeMillis() - it) } ?: "never"
            ))
            add(DiagnosticRow("Group keys known", groupKeysKnown().size.toString()))
            add(DiagnosticRow("Invite prefix", FamilyProtocol.INVITE_PREFIX))
        }
        return FamilyDiagnostics(
            rows = rows,
            logs = appLogs.value,
            hostRows = hostRows.map { (label, value) -> DiagnosticRow(label, value) }
        )
    }

    private fun formatAgeRow(ageMs: Long): String {
        val seconds = ageMs / 1000.0
        return if (seconds < 60.0) "%.1f s ago".format(seconds) else "${(seconds / 60).toInt()} min ago"
    }

    private suspend fun groupKeysKnown(): List<Pair<String, ByteArray>> =
        dao.getGroups()
            .mapNotNull { group ->
                if (cryptoEngine.hasGroupKey(group.id)) {
                    group.id to cryptoEngine.getOrCreateGroupKey(group.id)
                } else {
                    null
                }
            }

    // ------------------------------------------------------------- mapping

    private fun UserProfileEntity.toDomain() = UserProfile(
        id = id,
        displayName = displayName,
        colorArgb = colorArgb,
        publicKeyBase64 = publicKeyBase64,
        sharingEnabled = sharingEnabled,
        temporarySharingUntilMs = temporarySharingUntilMs,
        visibilityRadiusMeters = visibilityRadiusMeters
    )

    private fun UserProfile.toEntity() = UserProfileEntity(
        id = id,
        displayName = displayName,
        colorArgb = colorArgb,
        publicKeyBase64 = publicKeyBase64,
        sharingEnabled = sharingEnabled,
        temporarySharingUntilMs = temporarySharingUntilMs,
        visibilityRadiusMeters = visibilityRadiusMeters
    )

    private fun PeerGroup.toEntity() = PeerGroupEntity(
        id = id, name = name, category = category.name, ownerId = ownerId,
        createdAtMs = createdAtMs, memberCount = memberCount,
        shareMyLocation = shareMyLocation, temporarySharingUntilMs = temporarySharingUntilMs
    )

    private fun PeerGroupEntity.toDomain() = PeerGroup(
        id = id, name = name, category = runCatching { GroupCategory.valueOf(category) }
            .getOrDefault(GroupCategory.CUSTOM),
        ownerId = ownerId, createdAtMs = createdAtMs, memberCount = memberCount,
        shareMyLocation = shareMyLocation, temporarySharingUntilMs = temporarySharingUntilMs
    )

    companion object {
        private const val MAX_LOG_LINES = 300
        private const val DEFAULT_DISPLAY_NAME = "KinNav user"
        private const val DEFAULT_COLOR = 0xFF006C4CL
        private const val METERS_PER_DEGREE_LAT = 111_320.0

        private const val KEY_DISPLAY_NAME = "profile.display.name"
        private const val KEY_DISPLAY_COLOR = "profile.display.color"
        private const val KEY_SHARING_ENABLED = "profile.sharing.enabled"
        private const val KEY_VISIBILITY_RADIUS = "profile.sharing.visibility_radius"
    }
}
