package com.sanepo.offgrid.engine

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

class BleBeaconEngine(
    private val context: Context,
    private val onBeaconSeen: ((BluetoothDevice, OffGridProtocol.BleBeaconPacket, Int) -> Unit)? = null,
    private val onBluetoothPowerChanged: ((Boolean) -> Unit)? = null,
    private val onRemoteSosTriggered: ((OffGridProtocol.BleBeaconPacket) -> Unit)? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val btAdapter: BluetoothAdapter? = btManager?.adapter

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var receiverRegistered = false
    private var running = false
    private var scanningActive = false
    private var advertisingActive = false
    private var lowLatencyScan = true
    private var lastAdvertiseRefreshMs = 0L
    private var pendingRefreshPosted = false

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        onBluetoothPowerChanged?.invoke(true)
                        if (running) {
                            startScanInternal()
                            restartAdvertiseInternal()
                        }
                    }
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                        onBluetoothPowerChanged?.invoke(false)
                        scanningActive = false
                        advertisingActive = false
                        updateRepoState("Bluetooth mati: aktifkan untuk Radar BLE dan Beacon SOS")
                    }
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            handleScanResult(result ?: return)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanningActive = false
            updateRepoState("Pindai BLE terkendala (kode $errorCode)")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertisingActive = true
            updateRepoState(
                if (scanningActive) "Beacon & Radar BLE aktif" else "Beacon BLE memancar"
            )
        }

        override fun onStartFailure(errorCode: Int) {
            advertisingActive = false
            updateRepoState("Pancaran BLE gagal (kode $errorCode)")
        }
    }

    fun start() {
        val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && btAdapter != null
        MeshRepository.updateSnapshot(forceImmediate = false) {
            copy(
                bleSupported = supported,
                bleEnabled = btAdapter?.isEnabled == true
            )
        }
        if (!supported) {
            updateRepoState("Perangkat ini tidak memiliki chip BLE")
            return
        }
        running = true
        registerStateReceiver()
        if (btAdapter?.isEnabled != true) {
            updateRepoState("Bluetooth mati: aktifkan untuk Radar BLE dan Beacon SOS")
            return
        }
        startScanInternal()
        restartAdvertiseInternal()
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        pendingRefreshPosted = false
        stopScanInternal()
        stopAdvertiseInternal()
        unregisterStateReceiver()
        MeshRepository.updateSnapshot(forceImmediate = false) {
            copy(bleAdvertising = false, bleScanning = false, bleStatusDetail = "BLE siaga")
        }
    }

    fun setLowLatencyScan(aggressive: Boolean) {
        if (lowLatencyScan == aggressive) return
        lowLatencyScan = aggressive
        if (running && btAdapter?.isEnabled == true) {
            stopScanInternal()
            startScanInternal()
        }
    }

    fun scheduleAdvertiseRefresh() {
        if (!running || btAdapter?.isEnabled != true) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastAdvertiseRefreshMs
        if (elapsed >= 1800L) {
            mainHandler.post { restartAdvertiseInternal() }
        } else if (!pendingRefreshPosted) {
            pendingRefreshPosted = true
            mainHandler.postDelayed({
                pendingRefreshPosted = false
                if (running) restartAdvertiseInternal()
            }, 1800L - elapsed)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanInternal() {
        if (!PermissionHelper.hasBleScanPermission(context)) {
            scanningActive = false
            updateRepoState("Butuh izin Bluetooth/Lokasi untuk memindai BLE")
            return
        }
        val adapter = btAdapter ?: return
        if (!adapter.isEnabled) return
        scanner = adapter.bluetoothLeScanner
        val activeScanner = scanner
        if (activeScanner == null) {
            scanningActive = false
            updateRepoState("Scanner BLE belum tersedia di perangkat ini")
            return
        }
        val mode = if (lowLatencyScan) {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        } else {
            ScanSettings.SCAN_MODE_BALANCED
        }
        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .build()
        try {
            activeScanner.stopScan(scanCallback)
        } catch (_: Throwable) {
        }
        try {
            activeScanner.startScan(emptyList(), settings, scanCallback)
            scanningActive = true
            updateRepoState(
                if (advertisingActive) "Beacon & Radar BLE aktif" else "Radar BLE memindai"
            )
        } catch (t: Throwable) {
            scanningActive = false
            updateRepoState("Gagal memulai pindai BLE: ${t.message ?: "izin ditolak"}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanInternal() {
        scanningActive = false
        if (!PermissionHelper.hasBleScanPermission(context)) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartAdvertiseInternal() {
        if (!running || btAdapter?.isEnabled != true) return
        if (!PermissionHelper.hasBleAdvertisePermission(context)) {
            advertisingActive = false
            updateRepoState("Butuh izin Bluetooth Advertise agar HP lain mendeteksi Anda")
            return
        }
        val adapter = btAdapter ?: return
        if (!adapter.isMultipleAdvertisementSupported && adapter.bluetoothLeAdvertiser == null) {
            advertisingActive = false
            updateRepoState(
                if (scanningActive) "Hanya terima BLE (chip tidak dukung pancar iklan BLE)"
                else "Chip BLE tidak mendukung pancar iklan"
            )
            return
        }
        advertiser = adapter.bluetoothLeAdvertiser
        val activeAdv = advertiser ?: return

        try {
            activeAdv.stopAdvertising(advertiseCallback)
        } catch (_: Throwable) {
        }

        val snap = MeshRepository.getSnapshot()
        val payload = OffGridProtocol.encodeBlePayload(
            OffGridProtocol.BleBeaconPacket(
                nodeId = snap.localNodeId,
                callsign = snap.localCallsign,
                channelNumber = snap.channelNumber,
                batteryPercent = snap.batteryPercent,
                isSos = snap.localSosActive,
                latitude = snap.localLatitude,
                longitude = snap.localLongitude
            )
        )

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                if (snap.localSosActive) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                else AdvertiseSettings.ADVERTISE_MODE_BALANCED
            )
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(OffGridProtocol.BLE_MANUFACTURER_ID, payload)
            .build()

        lastAdvertiseRefreshMs = System.currentTimeMillis()
        try {
            activeAdv.startAdvertising(settings, data, advertiseCallback)
        } catch (t: Throwable) {
            advertisingActive = false
            updateRepoState("Gagal memancarkan beacon BLE: ${t.message ?: "error"}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertiseInternal() {
        advertisingActive = false
        if (!PermissionHelper.hasBleAdvertisePermission(context)) return
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Throwable) {
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val raw = result.scanRecord?.getManufacturerSpecificData(OffGridProtocol.BLE_MANUFACTURER_ID)
            ?: return
        val packet = OffGridProtocol.decodeBlePayload(raw) ?: return
        val wasSos = MeshRepository.getPeersSorted().firstOrNull { it.nodeId == packet.nodeId }?.isSos == true
        MeshRepository.upsertPeerFromBle(packet, result.rssi)
        result.device?.let { dev ->
            onBeaconSeen?.invoke(dev, packet, result.rssi)
        }
        if (packet.isSos && !wasSos) {
            onRemoteSosTriggered?.invoke(packet)
        }
    }

    private fun registerStateReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(context, btStateReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterStateReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(btStateReceiver)
        } catch (_: Throwable) {
        }
        receiverRegistered = false
    }

    private fun updateRepoState(detail: String) {
        MeshRepository.updateSnapshot(forceImmediate = false) {
            copy(
                bleEnabled = btAdapter?.isEnabled == true,
                bleAdvertising = advertisingActive,
                bleScanning = scanningActive,
                bleStatusDetail = detail
            )
        }
    }
}
