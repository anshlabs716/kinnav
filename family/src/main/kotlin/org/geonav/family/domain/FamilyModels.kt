package org.geonav.family.domain

import kotlinx.serialization.Serializable

/**
 * Domain models for location sharing.
 *
 * Ported from Luogo-FOSS's Models.kt and trimmed to what sharing actually needs: the
 * local profile (master switch), the groups you share with ("contacts"), and the inbound
 * sightings of other people. Item finding, geofence history, offline regions and routing
 * models from the parent app were deliberately not ported — GeoNav already has its own.
 */

enum class ActivityState(val label: String, val targetUpdateIntervalMs: Long) {
    STATIONARY("Stationary", 15_000L),
    WALKING("Walking", 2_000L),
    RUNNING("Running", 1_800L),
    CYCLING("Cycling", 1_800L),
    DRIVING("Driving", 1_500L),
    // Labelled "Unknown" rather than "Moving": this is the fallback when the reported
    // activity cannot be determined, so calling it "Moving" would assert something untrue.
    UNKNOWN("Unknown", 2_000L);

    companion object {
        /**
         * Honest speed-only classification. Speed alone cannot separate running from
         * cycling, so the ambiguous band is [UNKNOWN] instead of a guess.
         */
        fun fromSpeed(speedMps: Float): ActivityState = when {
            speedMps < 0.5f -> STATIONARY
            speedMps < 2.0f -> WALKING
            speedMps < 8.0f -> UNKNOWN
            else -> DRIVING
        }
    }
}

/**
 * One location fix as the host app's positioning stack produced it.
 *
 * This is the input boundary of the sharing engine: GeoNav's fusion state is mapped onto
 * this type, so the family module never touches `LocationManager` itself.
 */
data class SharedFix(
    val latitude: Double,
    val longitude: Double,
    val timestampMs: Long,
    val horizontalAccuracyMeters: Float,
    val altitudeMeters: Double? = null,
    val speedMps: Float = 0f,
    val bearingDegrees: Float = 0f,
    val sourceSummary: String = "GNSS",
    val activityState: ActivityState = ActivityState.UNKNOWN,
    val isSuspiciousJump: Boolean = false
)

enum class GroupCategory(val label: String) {
    FRIENDS("Friends"),
    FAMILY("Family"),
    CUSTOM("Custom")
}

/**
 * The local user.
 *
 * [sharingEnabled] is the master "share my location" switch and defaults to **false**:
 * merging the two apps must never silently start sharing. Opting in is an explicit tap.
 */
@Serializable
data class UserProfile(
    val id: String,
    val displayName: String,
    val colorArgb: Long,
    val publicKeyBase64: String,
    val sharingEnabled: Boolean = false,
    val temporarySharingUntilMs: Long? = null,
    /**
     * Visibility radius in metres: recipients receive the position rounded to a grid of
     * this size (0 = share the precise fix). This is the "how precisely can people see
     * me" knob on the who-can-see-me screen.
     */
    val visibilityRadiusMeters: Int = 0
)

/**
 * A circle of people you share with — the per-contact opt-in unit.
 *
 * You share with a group by joining it (the invite carries the group key), and can switch
 * sharing off per group at any time.
 */
@Serializable
data class PeerGroup(
    val id: String,
    val name: String,
    val category: GroupCategory = GroupCategory.CUSTOM,
    val ownerId: String,
    val createdAtMs: Long,
    val memberCount: Int = 1,
    val shareMyLocation: Boolean = true,
    val temporarySharingUntilMs: Long? = null
)

/** A sighting of another person, as decrypted from an inbound group payload. */
@Serializable
data class PeerLocationState(
    val userId: String,
    val groupId: String,
    val displayName: String,
    val colorArgb: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val altitudeMeters: Double? = null,
    val speedMps: Float = 0f,
    val bearingDegrees: Float = 0f,
    val batteryPercent: Int? = null,
    val isCharging: Boolean = false,
    val activityState: ActivityState = ActivityState.WALKING,
    val sourceSummary: String = "E2EE relay",
    val timestampMs: Long,
    val isOnline: Boolean = true,
    val sharingPaused: Boolean = false
) {
    fun isStale(nowMs: Long = System.currentTimeMillis()): Boolean =
        (nowMs - timestampMs) > 120_000L

    fun ageMs(nowMs: Long = System.currentTimeMillis()): Long =
        (nowMs - timestampMs).coerceAtLeast(0L)
}

/** Relay transport configuration. Same shape as Luogo's, so persisted prefs stay compatible. */
data class NetworkConfig(
    val relayUrl: String = FamilyProtocolDefaults.RELAY_URL,
    val useTorOrbot: Boolean = false,
    val socks5Enabled: Boolean = false,
    val socks5Host: String = "127.0.0.1",
    val socks5Port: Int = 9050,
    val retentionDays: Int = 30
)

/** Indirection so the domain model does not depend on the [org.geonav.family] object. */
internal object FamilyProtocolDefaults {
    const val RELAY_URL = "https://relay.luogo.app"
}

/** One row of the debug diagnostics table. */
data class DiagnosticRow(val label: String, val value: String)

/** Everything the debug diagnostics screen shows, all read from real state. */
data class FamilyDiagnostics(
    val rows: List<DiagnosticRow>,
    val logs: List<String>,
    val hostRows: List<DiagnosticRow>
)

/** Formats a millisecond age the way the parent apps did: "12 s ago" / "3 min ago". */
fun formatAge(ageMs: Long): String {
    val seconds = ageMs / 1000.0
    return if (seconds < 60.0) "%.1f s ago".format(seconds) else "${(seconds / 60).toInt()} min ago"
}
