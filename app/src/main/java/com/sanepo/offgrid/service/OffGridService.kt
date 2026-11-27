package com.sanepo.offgrid.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sanepo.offgrid.R
import com.sanepo.offgrid.engine.BleBeaconEngine
import com.sanepo.offgrid.engine.CompassRadarEngine
import com.sanepo.offgrid.engine.LocalMeshTransport
import com.sanepo.offgrid.engine.MeshRepository
import com.sanepo.offgrid.engine.OffGridProtocol
import com.sanepo.offgrid.engine.PermissionHelper
import com.sanepo.offgrid.engine.PttAudioEngine
import com.sanepo.offgrid.engine.SosBeaconController
import com.sanepo.offgrid.model.ChatMessage
import com.sanepo.offgrid.ui.MainActivity
import java.util.UUID

class OffGridService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): OffGridService = this@OffGridService
    }

    companion object {
        const val ACTION_START = "com.sanepo.offgrid.action.START"
        const val ACTION_STOP = "com.sanepo.offgrid.action.STOP"

        private const val CHANNEL_RADIO = "sanepo_offgrid_radio"
        private const val CHANNEL_SOS = "sanepo_offgrid_sos"
        private const val NOTIF_SERVICE_ID = 4101
        private const val NOTIF_SOS_ID = 4102

        fun start(context: Context) {
            val intent = Intent(context, OffGridService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val binder = LocalBinder()
    private var bleEngine: BleBeaconEngine? = null
    private var meshTransport: LocalMeshTransport? = null
    private var pttAudioEngine: PttAudioEngine? = null
    private var compassRadarEngine: CompassRadarEngine? = null
    private var sosController: SosBeaconController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var batteryRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) {
                    val pct = ((level * 100f) / scale).toInt().coerceIn(0, 100)
                    MeshRepository.updateSnapshot(forceImmediate = false) {
                        copy(batteryPercent = pct)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        MeshRepository.init(this)
        createNotificationChannels()

        sosController = SosBeaconController(this)
        bleEngine = BleBeaconEngine(this) { bleSos ->
            showIncomingSosNotification(bleSos.callsign, bleSos.latitude, bleSos.longitude)
        }
        pttAudioEngine = PttAudioEngine(
            context = this,
            onSendAudioFrame = { bytes -> meshTransport?.sendAudioFrameToPeers(bytes) },
            onSendPttFloor = { speaking ->
                val snap = MeshRepository.getSnapshot()
                meshTransport?.sendControlToAll(
                    OffGridProtocol.ControlEnvelope.PttFloor(
                        senderId = snap.localNodeId,
                        senderCallsign = snap.localCallsign,
                        channelNumber = snap.channelNumber,
                        speaking = speaking
                    )
                )
            }
        )
        meshTransport = LocalMeshTransport(
            context = this,
            onAudioFrameReceived = { frame -> pttAudioEngine?.onIncomingAudioFrame(frame) },
            onRemoteChatReceived = { chat ->
                if (chat.isSos) {
                    showIncomingSosNotification(chat.senderCallsign, chat.latitude, chat.longitude)
                }
            },
            onRemoteSosReceived = { sos ->
                if (sos.active) {
                    showIncomingSosNotification(sos.senderCallsign, sos.latitude, sos.longitude)
                }
            }
        )
        compassRadarEngine = CompassRadarEngine(this) {
            bleEngine?.scheduleAdvertiseRefresh()
        }

        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        batteryRegistered = true

        acquireWakeLock()
        startForegroundSafe()
        startAllSubsystems()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfCleanly()
            return START_NOT_STICKY
        }
        startForegroundSafe()
        refreshSubsystemsAfterPermissions()
        return START_STICKY
    }

    override fun onDestroy() {
        stopAllSubsystems()
        if (batteryRegistered) {
            try {
                unregisterReceiver(batteryReceiver)
            } catch (_: Throwable) {
            }
            batteryRegistered = false
        }
        releaseWakeLock()
        super.onDestroy()
    }

    fun refreshSubsystemsAfterPermissions() {
        startForegroundSafe()
        bleEngine?.start()
        compassRadarEngine?.refreshLocationPermissions()
        updateServiceNotification()
    }

    fun updateIdentityAndBroadcast(callsign: String, channelNumber: Int) {
        MeshRepository.updateIdentity(this, callsign, channelNumber)
        bleEngine?.scheduleAdvertiseRefresh()
        meshTransport?.refreshLocalServiceIdentity()
        updateServiceNotification()
    }

    fun startPttTransmit(): Boolean = pttAudioEngine?.startTransmitting() == true

    fun stopPttTransmit(sendRoger: Boolean = true) {
        pttAudioEngine?.stopTransmitting(sendRoger)
    }

    fun setPttLatched(latched: Boolean) {
        MeshRepository.updateSnapshot(forceImmediate = true) {
            copy(isPttLatched = latched)
        }
        if (latched) {
            val ok = startPttTransmit()
            if (!ok) {
                MeshRepository.updateSnapshot(forceImmediate = true) {
                    copy(isPttLatched = false)
                }
            }
        } else {
            stopPttTransmit(sendRoger = true)
        }
    }

    fun sendChatMessage(text: String, includeGps: Boolean = false, isSos: Boolean = false) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val snap = MeshRepository.getSnapshot()
        val msgId = UUID.randomUUID().toString().take(12)
        val lat = if (includeGps || isSos) snap.localLatitude else null
        val lon = if (includeGps || isSos) snap.localLongitude else null
        val now = System.currentTimeMillis()

        val outgoing = ChatMessage(
            msgId = msgId,
            senderId = snap.localNodeId,
            senderCallsign = snap.localCallsign,
            channelNumber = snap.channelNumber,
            text = clean,
            timestampMs = now,
            isOutgoing = true,
            isSos = isSos,
            latitude = lat,
            longitude = lon
        )
        MeshRepository.appendOutgoingMessage(outgoing)
        meshTransport?.sendControlToAll(
            OffGridProtocol.ControlEnvelope.Chat(
                msgId = msgId,
                senderId = snap.localNodeId,
                senderCallsign = snap.localCallsign,
                channelNumber = snap.channelNumber,
                text = clean,
                timestampMs = now,
                isSos = isSos,
                latitude = lat,
                longitude = lon
            )
        )
    }

    fun toggleLocalSos(enableSiren: Boolean = true, enableFlash: Boolean = true): Boolean {
        val current = MeshRepository.getSnapshot().localSosActive
        val next = !current
        MeshRepository.updateSnapshot(forceImmediate = true) {
            copy(localSosActive = next)
        }
        val snap = MeshRepository.getSnapshot()
        meshTransport?.sendControlToAll(
            OffGridProtocol.ControlEnvelope.SosSignal(
                senderId = snap.localNodeId,
                senderCallsign = snap.localCallsign,
                active = next,
                latitude = snap.localLatitude,
                longitude = snap.localLongitude,
                batteryPercent = snap.batteryPercent
            )
        )
        bleEngine?.scheduleAdvertiseRefresh()
        if (next) {
            sosController?.startSosPatterns(enableSiren = enableSiren, enableMorseFlash = enableFlash)
            val coordNote = if (snap.localLatitude != null && snap.localLongitude != null) {
                " [GPS: %.5f, %.5f]".format(snap.localLatitude, snap.localLongitude)
            } else ""
            sendChatMessage("DARURAT SOS dari ${snap.localCallsign}$coordNote", includeGps = true, isSos = true)
        } else {
            sosController?.stopSosPatterns()
        }
        updateServiceNotification()
        return next
    }

    fun toggleManualTorch(): Boolean {
        val controller = sosController ?: return false
        val next = !controller.isManualTorchOn()
        controller.setManualTorch(next)
        return controller.isManualTorchOn()
    }

    fun hasFlashlight(): Boolean = sosController?.hasFlashlight() == true

    fun createWifiDirectGroup() {
        meshTransport?.createWifiDirectGroup()
    }

    fun discoverWifiDirectPeers() {
        meshTransport?.discoverPeersAndServices()
    }

    fun connectWifiDirectPeer(address: String) {
        meshTransport?.connectToWifiDirectPeer(address)
    }

    fun disconnectWifiDirect() {
        meshTransport?.disconnectWifiDirect()
    }

    fun stopSelfCleanly() {
        stopAllSubsystems()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startAllSubsystems() {
        pttAudioEngine?.startPlayback()
        bleEngine?.start()
        meshTransport?.start()
        compassRadarEngine?.start()
        MeshRepository.updateSnapshot(forceImmediate = true) {
            copy(isEngineRunning = true)
        }
    }

    private fun stopAllSubsystems() {
        sosController?.stopSosPatterns()
        pttAudioEngine?.stopAll()
        bleEngine?.stop()
        meshTransport?.stop()
        compassRadarEngine?.stop()
        MeshRepository.updateSnapshot(forceImmediate = true) {
            copy(isEngineRunning = false, isLocalTransmitting = false, localSosActive = false)
        }
    }

    private fun startForegroundSafe() {
        val notif = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var typeMask = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (PermissionHelper.hasAudioPermission(this)) {
                typeMask = typeMask or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (PermissionHelper.hasLocationPermission(this)) {
                typeMask = typeMask or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (PermissionHelper.hasWifiDirectPermission(this) || PermissionHelper.hasBleConnectPermission(this)) {
                typeMask = typeMask or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            try {
                startForeground(NOTIF_SERVICE_ID, notif, typeMask)
            } catch (_: Throwable) {
                try {
                    startForeground(NOTIF_SERVICE_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } catch (_: Throwable) {
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_SERVICE_ID, notif)
        } else {
            startForeground(NOTIF_SERVICE_ID, notif)
        }
    }

    private fun updateServiceNotification() {
        if (!PermissionHelper.hasNotificationPermission(this)) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIF_SERVICE_ID, buildServiceNotification())
    }

    private fun buildServiceNotification(): Notification {
        val snap = MeshRepository.getSnapshot()
        val peerCount = MeshRepository.getPeersSorted().size
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, OffGridService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (snap.localSosActive) {
            "SOS DARURAT AKTIF - CH-%02d".format(snap.channelNumber)
        } else {
            "${snap.localCallsign} | CH-%02d Siaga".format(snap.channelNumber)
        }
        val body = "Unit sekitar terdeteksi: $peerCount | ${snap.wifiDirectRole}"
        return NotificationCompat.Builder(this, CHANNEL_RADIO)
            .setSmallIcon(R.drawable.ic_radio_ptt)
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.action_stop_service), stopIntent)
            .build()
    }

    private fun showIncomingSosNotification(callsign: String, lat: Double?, lon: Double?) {
        if (!PermissionHelper.hasNotificationPermission(this)) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val gpsText = if (lat != null && lon != null) {
            "Koordinat: %.5f, %.5f".format(lat, lon)
        } else {
            "Cek Radar untuk perkiraan jarak sinyal BLE/Wi-Fi"
        }
        val notif = NotificationCompat.Builder(this, CHANNEL_SOS)
            .setSmallIcon(R.drawable.ic_sos_beacon)
            .setContentTitle("SINYAL SOS DARURAT dari $callsign")
            .setContentText(gpsText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        nm.notify(NOTIF_SOS_ID, notif)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val radioChan = NotificationChannel(
            CHANNEL_RADIO,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val sosChan = NotificationChannel(
            CHANNEL_SOS,
            getString(R.string.notif_channel_sos),
            NotificationManager.IMPORTANCE_HIGH
        )
        nm.createNotificationChannel(radioChan)
        nm.createNotificationChannel(sosChan)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SanepoOffGrid::MeshLock").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        wakeLock = null
    }
}
