package org.geonav.family.finding

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * The BLE half of item finding: advertise this device's lost items, and scan for other
 * people's advertised items.
 *
 * Advertisement payload: manufacturer-specific data under [FindingCrypto.MANUFACTURER_ID]
 * containing exactly the 16 rotating-id bytes. No device name, no service UUIDs — the
 * advertisement says nothing that outlives the hour.
 *
 * Scanning filters on the same company id with an empty data prefix (matches every
 * KinNav beacon regardless of which epoch it advertises), so a helper only learns
 * "some rotating id was here at this RSSI" — never which item, because the id is
 * opaque without the owner's key.
 *
 * Every entry point checks the runtime permission it needs and reports honestly (returns
 * false + logs) when Bluetooth is absent, off, or not permitted. Nothing is faked.
 */
class FindingBeacon(
    context: Context,
    private val onLog: (String) -> Unit
) {

    private val appContext = context.applicationContext

    private val adapter = runCatching {
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }.getOrNull()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    /** Live scan callback so [stopScanning] stops exactly what was started. */
    private var activeScanCallback: ScanCallback? = null

    fun hasBluetoothHardware(): Boolean = adapter != null

    private fun advertisePermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED

    private fun scanPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts advertising [rotatingIdBytes]. Returns false (and logs) when the hardware,
     * permissions or stack make it impossible — the caller must treat false as "not
     * advertising", never as "advertising failed silently".
     */
    fun startAdvertising(rotatingIdBytes: ByteArray): Boolean {
        stopAdvertising()
        if (adapter == null) {
            onLog("finding: no Bluetooth adapter — not advertising")
            return false
        }
        if (!adapter.isEnabled) {
            onLog("finding: Bluetooth is off — not advertising")
            return false
        }
        if (!advertisePermissionGranted()) {
            onLog("finding: BLUETOOTH_ADVERTISE not granted — not advertising")
            return false
        }
        val leAdvertiser = adapter.bluetoothLeAdvertiser
        if (leAdvertiser == null) {
            onLog("finding: this device cannot BLE advertise — not advertising")
            return false
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(false)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .addManufacturerData(FindingCrypto.MANUFACTURER_ID, rotatingIdBytes)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()

        advertiser = leAdvertiser
        leAdvertiser.startAdvertising(settings, data, advertiseCallback)
        onLog("finding: advertising rotating id ${FindingCrypto.hexOf(rotatingIdBytes)}")
        return true
    }

    fun stopAdvertising() {
        val leAdvertiser = advertiser ?: return
        runCatching { leAdvertiser.stopAdvertising(advertiseCallback) }
            .onFailure { onLog("finding: stopAdvertising failed: ${it.message}") }
        advertiser = null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            onLog("finding: advertisement failed to start (code $errorCode)")
            advertiser = null
        }
    }

    /**
     * Starts scanning for KinNav item beacons. [onSighting] receives the rotating id hex
     * and the RSSI for every beacon seen. Returns false when scanning cannot start.
     */
    fun startScanning(onSighting: (rotatingIdHex: String, rssiDbm: Int) -> Unit): Boolean {
        stopScanning()
        if (adapter == null) {
            onLog("finding: no Bluetooth adapter — not scanning")
            return false
        }
        if (!adapter.isEnabled) {
            onLog("finding: Bluetooth is off — not scanning")
            return false
        }
        if (!scanPermissionGranted()) {
            onLog("finding: BLUETOOTH_SCAN not granted — not scanning")
            return false
        }
        val leScanner = adapter.bluetoothLeScanner
        if (leScanner == null) {
            onLog("finding: BLE scanner unavailable — not scanning")
            return false
        }

        val filter = ScanFilter.Builder()
            .setManufacturerData(FindingCrypto.MANUFACTURER_ID, ByteArray(0))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord ?: return
                val idBytes = record.getManufacturerSpecificData(FindingCrypto.MANUFACTURER_ID) ?: return
                if (idBytes.size != FindingCrypto.ROTATING_ID_HEX_LENGTH / 2) return
                val hex = FindingCrypto.hexOf(idBytes)
                onSighting(hex, result.rssi)
            }

            override fun onScanFailed(errorCode: Int) {
                onLog("finding: scan failed (code $errorCode)")
                activeScanCallback = null
            }
        }

        scanner = leScanner
        activeScanCallback = callback
        leScanner.startScan(listOf(filter), settings, callback)
        onLog("finding: scanning for item beacons")
        return true
    }

    fun stopScanning() {
        val callback = activeScanCallback ?: return
        val leScanner = scanner
        if (leScanner != null) {
            runCatching { leScanner.stopScan(callback) }
                .onFailure { onLog("finding: stopScan failed: ${it.message}") }
        }
        activeScanCallback = null
        scanner = null
    }
}
