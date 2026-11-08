package org.geonav.family.finding

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.geonav.family.crypto.CryptoEngine
import org.geonav.family.domain.SharedFix
import org.geonav.family.network.RelayClient
import java.util.UUID

/**
 * Owner side and helper side of item finding.
 *
 * Owner side: items live here (key never leaves the device), "lost mode" advertises the
 * item's rotating id over BLE and refreshes it at every epoch boundary, and [locate]
 * asks the relay where the item was last seen.
 *
 * Helper side: when "help others find" is on, [FindingBeacon] scans for KinNav item
 * beacons; every sighting of a rotating id is reported to the relay together with this
 * device's current fix and the beacon's RSSI. Reports are deduplicated per id for
 * [REPORT_COOLDOWN_MS] so a static helper cannot flood the relay with repeats.
 *
 * Independence from the sharing master switch: finding never sends your location to any
 * *person*. It sends "I saw pseudonym X here" to the relay, which cannot resolve X
 * without the item key. The position for a report comes from [fixProvider] (the host's
 * positioning stack); when there is no fix, the sighting is dropped and logged rather
 * than reported with a stale or invented position.
 */
class FindingRepository(
    context: android.content.Context,
    private val prefs: SharedPreferences,
    private val relayClient: RelayClient,
    private val fixProvider: () -> SharedFix?,
    private val onLog: (String) -> Unit = {}
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val beacon = FindingBeacon(context) { message ->
        onLog(message)
        Log.d(TAG, message)
    }
    private val crypto = CryptoEngine()

    private val _items = MutableStateFlow(
        FindingItemCodec.decode(prefs.getString(KEY_ITEMS, null).orEmpty())
    )
    val items: StateFlow<List<FindingItem>> = _items.asStateFlow()

    private val _helpFindingEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_HELP_FINDING, false)
    )
    val helpFindingEnabled: StateFlow<Boolean> = _helpFindingEnabled.asStateFlow()

    /** The item currently advertised (lost mode), if any. Diagnostics + UI. */
    var advertisedItemId: String? = null
        private set

    private var advertiseJob: Job? = null
    private var scanJob: Job? = null

    /** rotating id hex -> last report time, in-memory dedup only. */
    private val reportedRecently = HashMap<String, Long>()

    init {
        // Lost mode and helper scan survive process restarts by intent; the BLE calls
        // fail honestly when permissions are missing until the user re-grants them.
        _items.value.firstOrNull { it.isLost }?.let { startAdvertisingLoop(it) }
        if (_helpFindingEnabled.value) startHelpFinding()
    }

    // ------------------------------------------------------------------ items

    fun addItem(name: String): FindingItem {
        val key = crypto.generateKey()
        val item = FindingItem(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Item" },
            keyBase64 = CryptoEngine.encodeBase64Url(key),
            createdAtMs = System.currentTimeMillis()
        )
        _items.value = _items.value + item
        persist()
        onLog("finding: added item \"${item.name}\"")
        return item
    }

    fun removeItem(itemId: String) {
        val item = _items.value.firstOrNull { it.id == itemId } ?: return
        if (item.isLost && advertisedItemId == itemId) stopLostModeInternal()
        _items.value = _items.value.filterNot { it.id == itemId }
        persist()
        onLog("finding: removed item \"${item.name}\"")
    }

    /**
     * Turns lost mode on or off. Lost mode makes THIS device shout the item's rotating
     * id over BLE so nearby KinNav helpers can report sightings.
     *
     * The flag is always persisted (it is the user's intent); the return value reports
     * whether the advertisement actually started, so the UI can say "Bluetooth refused"
     * instead of pretending.
     */
    fun setLostMode(itemId: String, lost: Boolean): Boolean {
        val index = _items.value.indexOfFirst { it.id == itemId }
        if (index < 0) return false
        _items.value = _items.value.mapIndexed { i, it -> if (i == index) it.copy(isLost = lost) else it }
        persist()
        return if (lost) {
            // One BLE advertiser per process: only the most recently lost item shouts.
            stopLostModeInternal()
            val item = _items.value[index]
            startAdvertisingLoop(item).also { started ->
                onLog(
                    if (started) "finding: lost mode on for \"${item.name}\""
                    else "finding: lost mode requested for \"${item.name}\" but advertising could not start"
                )
            }
        } else {
            if (advertisedItemId == itemId) stopLostModeInternal()
            onLog("finding: lost mode off for \"${_items.value.getOrNull(index)?.name}\"")
            true
        }
    }

    // -------------------------------------------------------------- advertising

    private fun startAdvertisingLoop(item: FindingItem): Boolean {
        stopAdvertisingLoop()
        val key = runCatching { CryptoEngine.decodeBase64Url(item.keyBase64) }.getOrNull()
        if (key == null || key.size != 32) {
            onLog("finding: item key unreadable for \"${item.name}\" — not advertising")
            return false
        }
        // Start immediately, then refresh on every epoch boundary.
        val started = beacon.startAdvertising(FindingCrypto.rotatingIdBytes(key, FindingCrypto.currentEpochHour()))
        if (!started) return false
        advertisedItemId = item.id
        advertiseJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val nextEpochStart = (FindingCrypto.currentEpochHour(now) + 1) * FindingCrypto.EPOCH_MS
                // Small overlap so a helper scanning across the boundary still sees us.
                delay((nextEpochStart - now).coerceAtLeast(1000L) + 2_000L)
                if (!isActive) break
                val stillLost = _items.value.firstOrNull { it.id == item.id }?.isLost == true
                if (!stillLost) break
                val newId = FindingCrypto.rotatingIdBytes(key, FindingCrypto.currentEpochHour())
                if (!beacon.startAdvertising(newId)) {
                    onLog("finding: advertisement refresh failed — stopping lost mode advertising")
                    advertisedItemId = null
                    break
                }
            }
        }
        return true
    }

    private fun stopLostModeInternal() {
        stopAdvertisingLoop()
        beacon.stopAdvertising()
        advertisedItemId = null
    }

    private fun stopAdvertisingLoop() {
        advertiseJob?.cancel()
        advertiseJob = null
    }

    // ------------------------------------------------------------ helper (scan)

    /**
     * Starts/stops helping others find their items. Returns whether scanning is now
     * actually running; false means Bluetooth or permissions refused — the flag stays
     * persisted either way, and a later call (e.g. after the user grants permissions)
     * can start it.
     */
    fun setHelpFindingEnabled(enabled: Boolean): Boolean {
        _helpFindingEnabled.value = enabled
        prefs.edit().putBoolean(KEY_HELP_FINDING, enabled).apply()
        return if (enabled) startHelpFinding() else stopHelpFinding()
    }

    private fun startHelpFinding(): Boolean {
        stopHelpFinding()
        val started = beacon.startScanning { idHex, rssi -> onBeaconSeen(idHex, rssi) }
        if (!started) return false
        scanJob = scope.launch {
            // Keep a long-lived scan alive; startScanning/stopScanning own the hardware.
            while (isActive) delay(60_000L)
        }
        onLog("finding: helping others find items (scan on)")
        return true
    }

    private fun stopHelpFinding(): Boolean {
        scanJob?.cancel()
        scanJob = null
        beacon.stopScanning()
        onLog("finding: helper scan off")
        return true
    }

    private fun onBeaconSeen(rotatingIdHex: String, rssiDbm: Int) {
        if (!FindingCrypto.isValidRotatingId(rotatingIdHex)) return
        val now = System.currentTimeMillis()
        synchronized(reportedRecently) {
            val last = reportedRecently[rotatingIdHex]
            if (last != null && now - last < REPORT_COOLDOWN_MS) return
            reportedRecently[rotatingIdHex] = now
        }
        val fix = fixProvider() ?: run {
            onLog("finding: saw beacon $rotatingIdHex but have no fix — not reporting")
            return
        }
        scope.launch {
            val ok = relayClient.reportSighting(
                reportId = UUID.randomUUID().toString(),
                rotatingBleIdHex = rotatingIdHex,
                latitude = fix.latitude,
                longitude = fix.longitude,
                accuracyMeters = fix.horizontalAccuracyMeters,
                rssiDbm = rssiDbm,
                timestampMs = now
            )
            if (ok) {
                onLog("finding: reported sighting of $rotatingIdHex (rssi $rssiDbm)")
            } else {
                // Allow a retry after the backoff window.
                synchronized(reportedRecently) { reportedRecently.remove(rotatingIdHex) }
                onLog("finding: relay rejected sighting report for $rotatingIdHex")
            }
        }
    }

    // ------------------------------------------------------------------ locate

    sealed class LocateOutcome {
        /** The relay had a sighting; position is real and freshly stored on the item. */
        data class Found(val item: FindingItem, val sighting: LatestSighting) : LocateOutcome()

        /** No sighting for the item's recent epochs. This is a normal, honest answer. */
        data object NotSeen : LocateOutcome()

        data class Error(val message: String) : LocateOutcome()
    }

    /**
     * Asks the relay where this item was last seen, walking back over the last
     * [EPOCHS_TO_TRY] hourly epochs. The first hit wins and is stored on the item.
     */
    suspend fun locate(itemId: String): LocateOutcome {
        val item = _items.value.firstOrNull { it.id == itemId }
            ?: return LocateOutcome.Error("Unknown item")
        val key = runCatching { CryptoEngine.decodeBase64Url(item.keyBase64) }.getOrNull()
            ?: return LocateOutcome.Error("Item key unreadable")

        val nowEpoch = FindingCrypto.currentEpochHour()
        for (back in 0 until EPOCHS_TO_TRY) {
            val epoch = nowEpoch - back
            val idHex = FindingCrypto.rotatingIdHex(key, epoch)
            val sighting = relayClient.latestSighting(idHex) ?: continue
            val updated = item.copy(
                lastSeenAtMs = sighting.timestampMs,
                lastSeenLatitude = sighting.latitude,
                lastSeenLongitude = sighting.longitude,
                lastSeenAccuracyMeters = sighting.accuracyMeters,
                lastSeenRssiDbm = sighting.rssiDbm
            )
            _items.value = _items.value.map { if (it.id == itemId) updated else it }
            persist()
            onLog("finding: \"${item.name}\" last seen at ${sighting.latitude}, ${sighting.longitude}")
            return LocateOutcome.Found(updated, sighting)
        }
        onLog("finding: no sighting found for \"${item.name}\" in the last $EPOCHS_TO_TRY h")
        return LocateOutcome.NotSeen
    }

    // ------------------------------------------------------------- persistence

    private fun persist() {
        prefs.edit().putString(KEY_ITEMS, FindingItemCodec.encode(_items.value)).apply()
    }

    companion object {
        private const val TAG = "KinNavFinding"
        private const val KEY_ITEMS = "finding.items.v1"
        private const val KEY_HELP_FINDING = "finding.help.enabled"
        private const val REPORT_COOLDOWN_MS = 5 * 60_000L

        /** Hours of rotating ids to query when locating (current + this many back). */
        private const val EPOCHS_TO_TRY = 6
    }
}
