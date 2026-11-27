package com.sanepo.offgrid.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.location.LocationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.sanepo.offgrid.OffGridApp
import com.sanepo.offgrid.R
import com.sanepo.offgrid.engine.AppUpdateManager
import com.sanepo.offgrid.engine.GeoMath
import com.sanepo.offgrid.engine.MeshRepository
import com.sanepo.offgrid.engine.OffGridProtocol
import com.sanepo.offgrid.engine.PermissionHelper
import com.sanepo.offgrid.model.ChatMessage
import com.sanepo.offgrid.model.CompassAccuracyState
import com.sanepo.offgrid.model.MeshStatusSnapshot
import com.sanepo.offgrid.model.PeerNode
import com.sanepo.offgrid.model.RadarRangeMode
import com.sanepo.offgrid.model.TransportLinkType
import com.sanepo.offgrid.model.WifiDirectDiscoveredPeer
import com.sanepo.offgrid.service.OffGridService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity(), MeshRepository.Observer {

    private enum class Tab { RADIO, RADAR, CHAT, NODES }

    private val uiHandler = Handler(Looper.getMainLooper())
    private var currentTab = Tab.RADIO
    private var attachGpsToChat = false
    private var selectedRadarNodeId: String? = null
    private var currentRangeMode = RadarRangeMode.AUTO
    private var lastTargetAligned = false
    private var unreadChatCount = 0
    private var lastMessageCount = 0
    private var lastRenderedMessages: List<ChatMessage> = emptyList()
    private var hardwarePttActive = false
    private var showTechDiagnostics = false

    private var offGridService: OffGridService? = null
    private var serviceBound = false

    private val chatAdapter = ChatMessageAdapter { msg ->
        offGridService?.retryChatMessage(msg.msgId)
        Toast.makeText(this, "Mengirim ulang pesan...", Toast.LENGTH_SHORT).show()
    }
    private lateinit var radarPeerAdapter: PeerNodeAdapter
    private lateinit var allPeerAdapter: PeerNodeAdapter
    private lateinit var p2pDiscoveredAdapter: WifiDirectPeerAdapter

    // Views
    private lateinit var tvHeaderCallsign: TextView
    private lateinit var tvHeaderSummary: TextView
    private lateinit var btnQuickTorch: View
    private lateinit var viewUpdateBadge: View
    private lateinit var bannerSosAlert: View
    private lateinit var tvSosBannerText: TextView
    private lateinit var btnSosBannerAction: Button
    private lateinit var bannerPermission: View
    private lateinit var bannerHardwareToggle: View
    private lateinit var tvHardwareToggleInfo: TextView
    private lateinit var btnEnableBtQuick: Button
    private lateinit var btnEnableLocQuick: Button

    private lateinit var panelRadio: View
    private lateinit var panelRadar: View
    private lateinit var panelChat: View
    private lateinit var panelNodes: View

    private lateinit var tvRadioChannelBig: TextView
    private lateinit var tvRadioPeerCountBadge: TextView
    private lateinit var tvRadioFloorState: TextView
    private lateinit var tvRadioTransportSummary: TextView
    private lateinit var bannerCrossChannel: View
    private lateinit var tvCrossChannelMsg: TextView
    private lateinit var btnSwitchCrossChannel: Button
    private lateinit var waveformMeter: WaveformMeterView
    private lateinit var btnPttDome: View
    private lateinit var ivPttIcon: ImageView
    private lateinit var tvPttDomeLabel: TextView
    private lateinit var btnPttLatch: View
    private lateinit var tvLatchLabel: TextView
    private lateinit var btnTestAudio: Button
    private lateinit var btnSosHold: View
    private lateinit var tvSosBtnLabel: TextView
    private lateinit var progressSosHold: android.widget.ProgressBar

    private lateinit var tvRadarTargetTitle: TextView
    private lateinit var tvRadarTurnGuide: TextView
    private lateinit var tvRadarLocalGps: TextView
    private lateinit var tvRadarCalibrationWarn: TextView
    private lateinit var btnRadarRangeAuto: Button
    private lateinit var btnRadarRange50: Button
    private lateinit var btnRadarRange200: Button
    private lateinit var btnRadarRange1k: Button
    private lateinit var compassRadarView: CompassRadarView
    private lateinit var cardRadarNoGpsPeers: View
    private lateinit var tvRadarProximityList: TextView
    private lateinit var tvRadarEmptyState: View

    private lateinit var rvChatMessages: androidx.recyclerview.widget.RecyclerView
    private lateinit var chatEmptyState: View
    private lateinit var tvChatEmptyMsg: TextView
    private lateinit var btnAttachGpsChat: View
    private lateinit var etChatInput: EditText

    private lateinit var tvNetworkHeroStatus: TextView
    private lateinit var tvNetworkHeroSub: TextView
    private lateinit var btnNetworkRescanAll: Button
    private lateinit var tvP2pSimpleStatus: TextView
    private lateinit var tvHotspotStatusPill: TextView
    private lateinit var btnOpenWifiHotspotSettings: Button
    private lateinit var btnToggleTechInfo: Button
    private lateinit var tvNodeDiagnostics: TextView
    private lateinit var btnDisconnectP2p: Button
    private lateinit var tvP2pEmpty: View
    private lateinit var tvMeshEmpty: View

    private lateinit var tabViews: Map<Tab, Triple<View, ImageView, TextView>>

    private var sosHoldRunning = false
    private var sosHoldProgress = 0
    private val sosHoldRunnable = object : Runnable {
        override fun run() {
            if (!sosHoldRunning) return
            sosHoldProgress += 5
            progressSosHold.progress = sosHoldProgress.coerceIn(0, 100)
            if (sosHoldProgress >= 100) {
                sosHoldRunning = false
                progressSosHold.progress = 0
                btnSosHold.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val svc = offGridService
                if (svc != null) {
                    svc.toggleLocalSos(enableSiren = true, enableFlash = svc.hasFlashlight())
                }
            } else {
                uiHandler.postDelayed(this, 100L)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePermissionBanner()
        offGridService?.refreshSubsystemsAfterPermissions()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = (binder as? OffGridService.LocalBinder)?.getService() ?: return
            offGridService = local
            serviceBound = true
            btnQuickTorch.visibility = if (local.hasFlashlight()) View.VISIBLE else View.GONE
            local.refreshSubsystemsAfterPermissions()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            offGridService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MeshRepository.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        bindViews()
        setupInsets()
        setupAdapters()
        setupInteractions()
        selectTab(Tab.RADIO)
        selectRadarRange(RadarRangeMode.AUTO)

        OffGridService.start(this)
        requestMissingPermissionsIfNeeded(initialLaunch = true)
        checkSilentOtaBadge()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, OffGridService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        MeshRepository.addObserver(this)
        updatePermissionBanner()
    }

    override fun onStop() {
        sosHoldRunning = false
        uiHandler.removeCallbacks(sosHoldRunnable)
        if (!MeshRepository.getSnapshot().isPttLatched) {
            offGridService?.stopPttTransmit(sendRoger = false)
        }
        MeshRepository.removeObserver(this)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && currentTab == Tab.RADIO) {
            if (event?.repeatCount == 0 && !hardwarePttActive) {
                hardwarePttActive = true
                if (!MeshRepository.getSnapshot().isPttLatched) {
                    offGridService?.startPttTransmit()
                } else {
                    offGridService?.setPttLatched(false)
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && hardwarePttActive) {
            hardwarePttActive = false
            if (!MeshRepository.getSnapshot().isPttLatched) {
                offGridService?.stopPttTransmit(sendRoger = true)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onMeshUpdated(
        snapshot: MeshStatusSnapshot,
        peers: List<PeerNode>,
        wifiDirectPeers: List<WifiDirectDiscoveredPeer>,
        messages: List<ChatMessage>
    ) {
        val readyPeers = peers.filter { it.hasTwoWayLink() }
        val signalOnlyCount = peers.size - readyPeers.size
        val sameChReady = readyPeers.count { it.channelNumber == snapshot.channelNumber }

        tvHeaderCallsign.text = "${snapshot.localCallsign} | CH-%02d".format(snapshot.channelNumber)
        tvHeaderSummary.text = when {
            readyPeers.isNotEmpty() && snapshot.relayedPeersCount > 0 ->
                "${readyPeers.size} siap bicara (Estafet Mesh aktif)"
            readyPeers.isNotEmpty() ->
                "${readyPeers.size} teman siap bicara & chat"
            signalOnlyCount > 0 ->
                "$signalOnlyCount sinyal terdeteksi · Menyambungkan..."
            else ->
                "Belum terhubung · Ketuk Sambungan"
        }

        // SOS alert banner
        val remoteSos = snapshot.remoteSosNodes
        when {
            snapshot.localSosActive -> {
                bannerSosAlert.visibility = View.VISIBLE
                tvSosBannerText.text = "DARURAT SOS LOKAL AKTIF (Semua jalur memancar)"
                btnSosBannerAction.text = "Matikan"
                btnSosBannerAction.setOnClickListener {
                    offGridService?.toggleLocalSos()
                }
            }
            remoteSos.isNotEmpty() -> {
                val first = remoteSos.first()
                bannerSosAlert.visibility = View.VISIBLE
                tvSosBannerText.text = "SINYAL SOS dari ${first.callsign}!"
                btnSosBannerAction.text = "Cek Radar"
                btnSosBannerAction.setOnClickListener {
                    selectedRadarNodeId = first.nodeId
                    selectTab(Tab.RADAR)
                }
            }
            else -> bannerSosAlert.visibility = View.GONE
        }

        // Tab 1: Radio PTT
        tvRadioChannelBig.text = "SALURAN CH-%02d".format(snapshot.channelNumber)
        tvRadioPeerCountBadge.text = if (readyPeers.isNotEmpty()) {
            "$sameChReady/${readyPeers.size} Siap"
        } else {
            "0 Siap"
        }

        val rxActive = (snapshot.activeTalkerCallsign != null || snapshot.isAudioSelfTestRunning) && !snapshot.isLocalTransmitting
        waveformMeter.updateAudioState(
            levelNormalized = if (snapshot.isLocalTransmitting || snapshot.micLevelNormalized > snapshot.rxLevelNormalized) {
                snapshot.micLevelNormalized
            } else {
                snapshot.rxLevelNormalized
            },
            txActive = snapshot.isLocalTransmitting || snapshot.isAudioSelfTestRunning,
            rxActive = rxActive
        )

        btnPttDome.isSelected = snapshot.isLocalTransmitting
        val pttTextColor = ContextCompat.getColor(
            this,
            if (snapshot.isLocalTransmitting) R.color.ink_on_accent else R.color.ink_primary
        )
        ivPttIcon.setColorFilter(pttTextColor)
        tvPttDomeLabel.setTextColor(pttTextColor)
        tvPttDomeLabel.text = when {
            snapshot.isPttLatched -> "MIC DIKUNCI (TX)"
            snapshot.isLocalTransmitting -> "MENGIRIM LIVE..."
            else -> "TAHAN BICARA"
        }

        val speakerMuted = offGridService?.isSpeakerMuted() == true
        tvRadioFloorState.text = when {
            snapshot.isAudioSelfTestRunning -> "TES MIC & SPEAKER: Silakan bicara dekat HP..."
            snapshot.isLocalTransmitting -> "BICARA LIVE [${snapshot.activeAudioTransport}] · Paket ${snapshot.txAudioPackets}"
            snapshot.activeTalkerCallsign != null -> "MENERIMA DARI ${snapshot.activeTalkerCallsign} · Paket ${snapshot.rxAudioPackets}"
            speakerMuted -> "PERHATIAN: Volume Media HP 0%! Naikkan volume agar suara terdengar."
            snapshot.lastError != null -> snapshot.lastError
            else -> "SIAGA DENGAR : TX ${snapshot.txAudioPackets} | RX ${snapshot.rxAudioPackets} paket"
        }

        btnTestAudio.isEnabled = !snapshot.isAudioSelfTestRunning && !snapshot.isLocalTransmitting
        btnTestAudio.text = if (snapshot.isAudioSelfTestRunning) {
            "Menguji Mic & Speaker..."
        } else {
            "Tes Mic & Speaker Lokal (2 Detik)"
        }

        val transportReadable = buildReadableRadioLinkSummary(snapshot, readyPeers)
        tvRadioTransportSummary.text = transportReadable

        val crossCh = snapshot.crossChannelAlertNumber
        if (crossCh != null && crossCh != snapshot.channelNumber && !snapshot.crossChannelAlertText.isNullOrBlank()) {
            bannerCrossChannel.visibility = View.VISIBLE
            tvCrossChannelMsg.text = snapshot.crossChannelAlertText
            btnSwitchCrossChannel.text = "Pindah CH-%02d".format(crossCh)
            btnSwitchCrossChannel.setOnClickListener {
                offGridService?.updateIdentityAndBroadcast(snapshot.localCallsign, crossCh)
            }
        } else {
            bannerCrossChannel.visibility = View.GONE
        }

        updateHardwareBanner(snapshot)

        tvLatchLabel.text = if (snapshot.isPttLatched) "Kunci Mic: AKTIF" else "Kunci Mic: MATI"
        btnPttLatch.setBackgroundResource(if (snapshot.isPttLatched) R.drawable.bg_accent_btn else R.drawable.bg_keycap_btn)
        tvLatchLabel.setTextColor(ContextCompat.getColor(this, if (snapshot.isPttLatched) R.color.ink_on_accent else R.color.ink_primary))

        tvSosBtnLabel.text = if (snapshot.localSosActive) {
            "KETUK UNTUK MATIKAN SOS DARURAT"
        } else {
            "TAHAN 2 DETIK : PANCARKAN DARURAT SOS"
        }

        // Tab 2: Tactical Compass Radar
        tvRadarLocalGps.text = snapshot.gpsStatusText
        tvRadarCalibrationWarn.visibility = if (snapshot.compassAccuracy == CompassAccuracyState.LOW_NEEDS_CALIBRATION) {
            View.VISIBLE
        } else {
            View.GONE
        }
        compassRadarView.updateRadarData(
            localLatitude = snapshot.localLatitude,
            localLongitude = snapshot.localLongitude,
            azimuthDeg = snapshot.deviceAzimuthDeg,
            peerList = peers,
            selectedId = selectedRadarNodeId
        )
        radarPeerAdapter.submitList(peers, snapshot.localLatitude, snapshot.localLongitude)
        tvRadarEmptyState.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
        updateRadarTargetHudAndProximity(snapshot, peers)

        // Tab 3: Guaranteed Chat
        if (messages != lastRenderedMessages) {
            val prevCount = lastMessageCount
            if (messages.size > prevCount && currentTab != Tab.CHAT && prevCount > 0) {
                unreadChatCount += (messages.size - prevCount)
            }
            lastMessageCount = messages.size
            lastRenderedMessages = messages
            chatAdapter.submitList(messages, peers)
            if (messages.size > prevCount && messages.isNotEmpty()) {
                rvChatMessages.scrollToPosition(messages.size - 1)
            }
        }
        chatEmptyState.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        tvChatEmptyMsg.text = "Belum ada pesan di CH-%02d. Setiap pesan dikirim ulang otomatis dan tersinkron saat sinyal kembali.".format(snapshot.channelNumber)
        tabViews[Tab.CHAT]?.third?.text = if (unreadChatCount > 0) "Chat ($unreadChatCount)" else getString(R.string.tab_chat)

        // Tab 4: Simple Connection Hub
        updateConnectionHubUi(snapshot, peers, readyPeers, signalOnlyCount, wifiDirectPeers)
    }

    private fun buildReadableRadioLinkSummary(
        snapshot: MeshStatusSnapshot,
        readyPeers: List<PeerNode>
    ): String {
        val hasWifiPath = snapshot.connectedTcpSockets > 0 ||
            snapshot.wifiDirectRole.startsWith("Ketua") ||
            snapshot.wifiDirectRole.startsWith("Anggota") ||
            snapshot.hotspotActive
        val hasBtPath = snapshot.bleGattConnectedCount > 0
        val parts = mutableListOf<String>()
        if (hasWifiPath) {
            if (snapshot.hotspotActive) parts.add("Hotspot/Wi-Fi") else parts.add("Wi-Fi Direct")
        }
        if (hasBtPath) parts.add("Bluetooth")
        if (snapshot.relayedPeersCount > 0) parts.add("Estafet ${snapshot.relayedPeersCount} HP")

        return when {
            readyPeers.isNotEmpty() && parts.isNotEmpty() ->
                "Jalur Aktif: ${parts.joinToString(" + ")} · ${readyPeers.size} teman siap"
            parts.isNotEmpty() ->
                "Jalur Siap (${parts.joinToString(" + ")}) · Menunggu teman"
            else ->
                "Belum tersambung · Ketuk untuk buka menu Sambungan"
        }
    }

    private fun updateConnectionHubUi(
        snapshot: MeshStatusSnapshot,
        allPeers: List<PeerNode>,
        readyPeers: List<PeerNode>,
        signalOnlyCount: Int,
        wifiDirectPeers: List<WifiDirectDiscoveredPeer>
    ) {
        when {
            readyPeers.isNotEmpty() -> {
                val relayNote = if (snapshot.relayedPeersCount > 0) " · Estafet Aktif" else ""
                tvNetworkHeroStatus.text = "Tersambung Siap Bicara (${readyPeers.size} Teman)$relayNote"
                tvNetworkHeroStatus.setTextColor(ContextCompat.getColor(this, R.color.status_live))
            }
            signalOnlyCount > 0 -> {
                tvNetworkHeroStatus.text = "Mendeteksi $signalOnlyCount Sinyal · Menyambung Otomatis..."
                tvNetworkHeroStatus.setTextColor(ContextCompat.getColor(this, R.color.status_warn))
            }
            else -> {
                tvNetworkHeroStatus.text = "Belum Terhubung ke Teman"
                tvNetworkHeroStatus.setTextColor(ContextCompat.getColor(this, R.color.ink_primary))
            }
        }

        val btTxt = when {
            snapshot.bleGattConnectedCount > 0 -> "Bluetooth Tersambung (${snapshot.bleGattConnectedCount})"
            snapshot.bleEnabled -> "Bluetooth Siap"
            else -> "Bluetooth Mati"
        }
        val wifiTxt = when {
            snapshot.hotspotActive -> "Hotspot Aktif"
            snapshot.wifiDirectRole.startsWith("Ketua") || snapshot.wifiDirectRole.startsWith("Anggota") -> snapshot.wifiDirectRole
            snapshot.wifiEnabled -> "Wi-Fi Siap"
            else -> "Wi-Fi Mati"
        }
        val gpsTxt = if (snapshot.localLatitude != null) "GPS Terkunci" else "GPS Mencari"
        tvNetworkHeroSub.text = "$btTxt · $wifiTxt · $gpsTxt"

        tvP2pSimpleStatus.text = "Status: ${snapshot.wifiDirectRole} · ${snapshot.wifiDirectDetail}"
        val inGroup = snapshot.wifiDirectRole.startsWith("Ketua") || snapshot.wifiDirectRole.startsWith("Anggota")
        btnDisconnectP2p.visibility = if (inGroup) View.VISIBLE else View.GONE

        val lanAvailable = snapshot.hotspotActive || snapshot.localLanIps.any { !it.startsWith("192.168.49.") }
        if (lanAvailable) {
            tvHotspotStatusPill.text = if (snapshot.hotspotActive) {
                "Hotspot HP Aktif · Teman yang gabung Wi-Fi otomatis tersambung"
            } else {
                "Wi-Fi Lokal Tersambung · Otomatis siap chat & suara HD"
            }
            tvHotspotStatusPill.setBackgroundResource(R.drawable.bg_well_live)
        } else {
            tvHotspotStatusPill.text = "Hotspot / Wi-Fi Lokal belum tersambung"
            tvHotspotStatusPill.setBackgroundResource(R.drawable.bg_status_chip)
        }

        p2pDiscoveredAdapter.submitList(wifiDirectPeers)
        tvP2pEmpty.visibility = if (wifiDirectPeers.isEmpty()) View.VISIBLE else View.GONE

        allPeerAdapter.submitList(allPeers, snapshot.localLatitude, snapshot.localLongitude)
        tvMeshEmpty.visibility = if (allPeers.isEmpty()) View.VISIBLE else View.GONE

        val ipText = if (snapshot.localLanIps.isEmpty()) "-" else snapshot.localLanIps.joinToString(", ")
        tvNodeDiagnostics.text = buildString {
            appendLine("Node ID: ${snapshot.localNodeId} (${snapshot.localCallsign})")
            appendLine("BLE: ${snapshot.bleStatusDetail} | GATT Aktif: ${snapshot.bleGattConnectedCount}")
            appendLine("Wi-Fi Direct: ${snapshot.wifiDirectRole} | TCP Aktif: ${snapshot.connectedTcpSockets}")
            appendLine("IP Lokal: $ipText | Estafet Peer: ${snapshot.relayedPeersCount}")
            append("Baterai: ${snapshot.batteryPercent}%")
        }
    }

    private fun updateRadarTargetHudAndProximity(snapshot: MeshStatusSnapshot, peers: List<PeerNode>) {
        val hdg = GeoMath.normalizeAzimuth(snapshot.deviceAzimuthDeg.toDouble()).toInt()
        val hdgCard = GeoMath.cardinalDirectionId(hdg.toDouble())
        val lat = snapshot.localLatitude
        val lon = snapshot.localLongitude

        // Honest non-GPS proximity summary
        val nonGpsPeers = peers.filter { lat == null || lon == null || it.latitude == null || it.longitude == null }
        if (nonGpsPeers.isEmpty()) {
            cardRadarNoGpsPeers.visibility = View.GONE
        } else {
            cardRadarNoGpsPeers.visibility = View.VISIBLE
            tvRadarProximityList.text = nonGpsPeers.joinToString("\n") { p ->
                val sigLabel = when {
                    p.estimatedBleMeters != null -> {
                        val band = when {
                            p.estimatedBleMeters <= 5.0 -> "Sangat Dekat"
                            p.estimatedBleMeters <= 16.0 -> "Sedang"
                            else -> "Agak Jauh"
                        }
                        "$band (~${GeoMath.formatDistanceCompact(p.estimatedBleMeters)})"
                    }
                    p.hopCount > 0 -> "Estafet ${p.hopCount}x (menunggu GPS)"
                    else -> "Tersambung Wi-Fi (menunggu GPS)"
                }
                "• ${p.callsign} : $sigLabel"
            }
        }

        val target = peers.firstOrNull { it.nodeId == selectedRadarNodeId }
            ?: peers.firstOrNull { it.latitude != null && it.longitude != null }
            ?: peers.firstOrNull()

        if (target == null) {
            lastTargetAligned = false
            tvRadarTargetTitle.text = "Belum ada teman terdeteksi di radar"
            tvRadarTurnGuide.text = "Arah hadap Anda: %03d° ($hdgCard)".format(hdg)
            tvRadarTurnGuide.setTextColor(ContextCompat.getColor(this, R.color.ink_secondary))
            return
        }

        val ageSec = ((System.currentTimeMillis() - target.lastSeenMs) / 1000L).coerceAtLeast(0L)
        val freshLabel = if (ageSec < 5L) "Live" else "${ageSec} dtk lalu"
        val linkNote = if (target.hopCount > 0) "Estafet ${target.hopCount}x" else "Langsung"

        if (lat != null && lon != null && target.latitude != null && target.longitude != null) {
            val meters = GeoMath.haversineDistanceMeters(lat, lon, target.latitude, target.longitude)
            val bearing = GeoMath.initialBearingDegrees(lat, lon, target.latitude, target.longitude)
            val card = GeoMath.cardinalDirectionId(bearing)
            val turn = GeoMath.signedRelativeTurnDegrees(bearing, snapshot.deviceAzimuthDeg.toDouble())
            val absTurn = abs(turn).toInt()

            tvRadarTargetTitle.text = "TARGET: ${target.callsign} · $linkNote ($freshLabel)"
            if (absTurn <= 10) {
                tvRadarTurnGuide.text = "LURUS DI DEPAN · ${GeoMath.formatDistanceCompact(meters)} (${bearing.toInt()}° $card)"
                tvRadarTurnGuide.setTextColor(ContextCompat.getColor(this, R.color.status_live))
                if (!lastTargetAligned && currentTab == Tab.RADAR) {
                    compassRadarView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                lastTargetAligned = true
            } else {
                lastTargetAligned = false
                val dirWord = if (turn > 0) "BELOK KANAN" else "BELOK KIRI"
                tvRadarTurnGuide.text = "$dirWord $absTurn° · ${GeoMath.formatDistanceCompact(meters)} (${bearing.toInt()}° $card)"
                tvRadarTurnGuide.setTextColor(ContextCompat.getColor(this, R.color.accent_ink))
            }
        } else if (target.estimatedBleMeters != null) {
            lastTargetAligned = false
            tvRadarTargetTitle.text = "TARGET: ${target.callsign} · $freshLabel"
            tvRadarTurnGuide.text = "Jarak sinyal ~${GeoMath.formatDistanceCompact(target.estimatedBleMeters)} (tunggu GPS untuk arah kompas)"
            tvRadarTurnGuide.setTextColor(ContextCompat.getColor(this, R.color.status_warn))
        } else {
            lastTargetAligned = false
            tvRadarTargetTitle.text = "TARGET: ${target.callsign} · $linkNote"
            tvRadarTurnGuide.text = "Menunggu GPS teman aktif · Arah Anda %03d° ($hdgCard)".format(hdg)
            tvRadarTurnGuide.setTextColor(ContextCompat.getColor(this, R.color.ink_secondary))
        }
    }

    private fun selectRadarRange(mode: RadarRangeMode) {
        currentRangeMode = mode
        compassRadarView.setRangeMode(mode)
        val buttons = listOf(
            RadarRangeMode.AUTO to btnRadarRangeAuto,
            RadarRangeMode.M50 to btnRadarRange50,
            RadarRangeMode.M200 to btnRadarRange200,
            RadarRangeMode.KM1 to btnRadarRange1k
        )
        for ((m, btn) in buttons) {
            val active = m == mode
            btn.setBackgroundResource(if (active) R.drawable.bg_accent_btn else R.drawable.bg_keycap_btn)
            btn.setTextColor(ContextCompat.getColor(this, if (active) R.color.ink_on_accent else R.color.ink_primary))
        }
    }

    private fun bindViews() {
        tvHeaderCallsign = findViewById(R.id.tvHeaderCallsign)
        tvHeaderSummary = findViewById(R.id.tvHeaderSummary)
        btnQuickTorch = findViewById(R.id.btnQuickTorch)
        viewUpdateBadge = findViewById(R.id.viewUpdateBadge)
        bannerSosAlert = findViewById(R.id.bannerSosAlert)
        tvSosBannerText = findViewById(R.id.tvSosBannerText)
        btnSosBannerAction = findViewById(R.id.btnSosBannerAction)
        bannerPermission = findViewById(R.id.bannerPermission)
        bannerHardwareToggle = findViewById(R.id.bannerHardwareToggle)
        tvHardwareToggleInfo = findViewById(R.id.tvHardwareToggleInfo)
        btnEnableBtQuick = findViewById(R.id.btnEnableBtQuick)
        btnEnableLocQuick = findViewById(R.id.btnEnableLocQuick)

        panelRadio = findViewById(R.id.panelRadio)
        panelRadar = findViewById(R.id.panelRadar)
        panelChat = findViewById(R.id.panelChat)
        panelNodes = findViewById(R.id.panelNodes)

        tvRadioChannelBig = findViewById(R.id.tvRadioChannelBig)
        tvRadioPeerCountBadge = findViewById(R.id.tvRadioPeerCountBadge)
        tvRadioFloorState = findViewById(R.id.tvRadioFloorState)
        tvRadioTransportSummary = findViewById(R.id.tvRadioTransportSummary)
        bannerCrossChannel = findViewById(R.id.bannerCrossChannel)
        tvCrossChannelMsg = findViewById(R.id.tvCrossChannelMsg)
        btnSwitchCrossChannel = findViewById(R.id.btnSwitchCrossChannel)
        waveformMeter = findViewById(R.id.waveformMeter)
        btnPttDome = findViewById(R.id.btnPttDome)
        ivPttIcon = findViewById(R.id.ivPttIcon)
        tvPttDomeLabel = findViewById(R.id.tvPttDomeLabel)
        btnPttLatch = findViewById(R.id.btnPttLatch)
        tvLatchLabel = findViewById(R.id.tvLatchLabel)
        btnTestAudio = findViewById(R.id.btnTestAudio)
        btnSosHold = findViewById(R.id.btnSosHold)
        tvSosBtnLabel = findViewById(R.id.tvSosBtnLabel)
        progressSosHold = findViewById(R.id.progressSosHold)

        tvRadarTargetTitle = findViewById(R.id.tvRadarTargetTitle)
        tvRadarTurnGuide = findViewById(R.id.tvRadarTurnGuide)
        tvRadarLocalGps = findViewById(R.id.tvRadarLocalGps)
        tvRadarCalibrationWarn = findViewById(R.id.tvRadarCalibrationWarn)
        btnRadarRangeAuto = findViewById(R.id.btnRadarRangeAuto)
        btnRadarRange50 = findViewById(R.id.btnRadarRange50)
        btnRadarRange200 = findViewById(R.id.btnRadarRange200)
        btnRadarRange1k = findViewById(R.id.btnRadarRange1k)
        compassRadarView = findViewById(R.id.compassRadarView)
        cardRadarNoGpsPeers = findViewById(R.id.cardRadarNoGpsPeers)
        tvRadarProximityList = findViewById(R.id.tvRadarProximityList)
        tvRadarEmptyState = findViewById(R.id.tvRadarEmptyState)

        rvChatMessages = findViewById(R.id.rvChatMessages)
        chatEmptyState = findViewById(R.id.chatEmptyState)
        tvChatEmptyMsg = findViewById(R.id.tvChatEmptyMsg)
        btnAttachGpsChat = findViewById(R.id.btnAttachGpsChat)
        etChatInput = findViewById(R.id.etChatInput)

        tvNetworkHeroStatus = findViewById(R.id.tvNetworkHeroStatus)
        tvNetworkHeroSub = findViewById(R.id.tvNetworkHeroSub)
        btnNetworkRescanAll = findViewById(R.id.btnNetworkRescanAll)
        tvP2pSimpleStatus = findViewById(R.id.tvP2pSimpleStatus)
        tvHotspotStatusPill = findViewById(R.id.tvHotspotStatusPill)
        btnOpenWifiHotspotSettings = findViewById(R.id.btnOpenWifiHotspotSettings)
        btnToggleTechInfo = findViewById(R.id.btnToggleTechInfo)
        tvNodeDiagnostics = findViewById(R.id.tvNodeDiagnostics)
        btnDisconnectP2p = findViewById(R.id.btnDisconnectP2p)
        tvP2pEmpty = findViewById(R.id.tvP2pEmpty)
        tvMeshEmpty = findViewById(R.id.tvMeshEmpty)

        tabViews = mapOf(
            Tab.RADIO to Triple(findViewById(R.id.tabRadio), findViewById(R.id.ivTabRadio), findViewById(R.id.tvTabRadio)),
            Tab.RADAR to Triple(findViewById(R.id.tabRadar), findViewById(R.id.ivTabRadar), findViewById(R.id.tvTabRadar)),
            Tab.CHAT to Triple(findViewById(R.id.tabChat), findViewById(R.id.ivTabChat), findViewById(R.id.tvTabChat)),
            Tab.NODES to Triple(findViewById(R.id.tabNodes), findViewById(R.id.ivTabNodes), findViewById(R.id.tvTabNodes))
        )
    }

    private fun setupInsets() {
        val root = findViewById<View>(R.id.mainRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupAdapters() {
        val onPeerPicked: (PeerNode) -> Unit = { peer ->
            selectedRadarNodeId = peer.nodeId
            selectTab(Tab.RADAR)
            val snap = MeshRepository.getSnapshot()
            val list = MeshRepository.getPeersSorted()
            updateRadarTargetHudAndProximity(snap, list)
            compassRadarView.updateRadarData(
                snap.localLatitude,
                snap.localLongitude,
                snap.deviceAzimuthDeg,
                list,
                selectedRadarNodeId
            )
        }
        radarPeerAdapter = PeerNodeAdapter(onSelectPeer = onPeerPicked, onPingPeer = null)
        allPeerAdapter = PeerNodeAdapter(
            onSelectPeer = onPeerPicked,
            onPingPeer = { peer ->
                offGridService?.sendPingToPeer(peer.nodeId)
                Toast.makeText(this, "Menguji Ping ke ${peer.callsign}...", Toast.LENGTH_SHORT).show()
            }
        )
        p2pDiscoveredAdapter = WifiDirectPeerAdapter { discovered ->
            offGridService?.connectWifiDirectPeer(discovered.deviceAddress)
            Toast.makeText(this, "Menghubungkan ke ${discovered.callsign ?: discovered.deviceName}...", Toast.LENGTH_SHORT).show()
        }

        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvRadarPeers).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = radarPeerAdapter
        }
        rvChatMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = chatAdapter
        }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvP2pDiscovered).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = p2pDiscoveredAdapter
        }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNodesAll).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = allPeerAdapter
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInteractions() {
        findViewById<View>(R.id.btnEditIdentity).setOnClickListener {
            showEditIdentityDialog()
        }

        tvRadioTransportSummary.setOnClickListener {
            selectTab(Tab.NODES)
        }

        btnQuickTorch.setOnClickListener {
            val svc = offGridService ?: return@setOnClickListener
            val on = svc.toggleManualTorch()
            btnQuickTorch.setBackgroundResource(if (on) R.drawable.bg_accent_btn else R.drawable.bg_keycap_btn)
            (btnQuickTorch as? ImageView)?.setColorFilter(
                ContextCompat.getColor(this, if (on) R.color.ink_on_accent else R.color.ink_primary)
            )
        }

        findViewById<View>(R.id.btnToggleTheme).setOnClickListener {
            val nextDark = !OffGridApp.isDarkModePreferred(this)
            OffGridApp.setDarkModePreferred(this, nextDark)
        }

        findViewById<View>(R.id.btnOpenAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        findViewById<Button>(R.id.btnGrantPermissions).setOnClickListener {
            requestMissingPermissionsIfNeeded(initialLaunch = false)
        }

        btnEnableBtQuick.setOnClickListener {
            openBluetoothEnablePrompt()
        }

        btnEnableLocQuick.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (_: Throwable) {
            }
        }

        // Tactile Push-To-Talk Dome
        btnPttDome.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (!PermissionHelper.hasAudioPermission(this)) {
                        requestMissingPermissionsIfNeeded(initialLaunch = false)
                    } else if (!MeshRepository.getSnapshot().isPttLatched) {
                        offGridService?.startPttTransmit()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!MeshRepository.getSnapshot().isPttLatched) {
                        offGridService?.stopPttTransmit(sendRoger = true)
                    }
                    v.performClick()
                    true
                }
                else -> false
            }
        }
        btnPttDome.setOnClickListener {
            if (MeshRepository.getSnapshot().isPttLatched) {
                offGridService?.setPttLatched(false)
            }
        }
        btnPttDome.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    val latched = MeshRepository.getSnapshot().isPttLatched
                    offGridService?.setPttLatched(!latched)
                }
                true
            } else false
        }

        btnPttLatch.setOnClickListener {
            if (!PermissionHelper.hasAudioPermission(this)) {
                requestMissingPermissionsIfNeeded(initialLaunch = false)
                return@setOnClickListener
            }
            val next = !MeshRepository.getSnapshot().isPttLatched
            offGridService?.setPttLatched(next)
        }

        findViewById<Button>(R.id.btnChannelPrev).setOnClickListener {
            val cur = MeshRepository.getSnapshot().channelNumber
            val prev = if (cur <= 1) 20 else cur - 1
            offGridService?.updateIdentityAndBroadcast(MeshRepository.getSnapshot().localCallsign, prev)
        }

        findViewById<Button>(R.id.btnChannelNext).setOnClickListener {
            val cur = MeshRepository.getSnapshot().channelNumber
            val next = if (cur >= 20) 1 else cur + 1
            offGridService?.updateIdentityAndBroadcast(MeshRepository.getSnapshot().localCallsign, next)
        }

        btnTestAudio.setOnClickListener {
            if (!PermissionHelper.hasAudioPermission(this)) {
                requestMissingPermissionsIfNeeded(initialLaunch = false)
                return@setOnClickListener
            }
            offGridService?.runLocalAudioSelfTest { _, msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }

        // Hold 2 sec SOS button
        btnSosHold.setOnTouchListener { v, event ->
            val snap = MeshRepository.getSnapshot()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (snap.localSosActive) {
                        return@setOnTouchListener false
                    }
                    sosHoldRunning = true
                    sosHoldProgress = 0
                    progressSosHold.progress = 0
                    uiHandler.postDelayed(sosHoldRunnable, 100L)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (snap.localSosActive) {
                        v.performClick()
                    } else {
                        sosHoldRunning = false
                        uiHandler.removeCallbacks(sosHoldRunnable)
                        progressSosHold.progress = 0
                    }
                    true
                }
                else -> false
            }
        }
        btnSosHold.setOnClickListener {
            val svc = offGridService ?: return@setOnClickListener
            if (MeshRepository.getSnapshot().localSosActive) {
                svc.toggleLocalSos()
            } else {
                Toast.makeText(this, "Tahan tombol merah 2 detik untuk memancarkan SOS.", Toast.LENGTH_SHORT).show()
            }
        }

        btnRadarRangeAuto.setOnClickListener { selectRadarRange(RadarRangeMode.AUTO) }
        btnRadarRange50.setOnClickListener { selectRadarRange(RadarRangeMode.M50) }
        btnRadarRange200.setOnClickListener { selectRadarRange(RadarRangeMode.M200) }
        btnRadarRange1k.setOnClickListener { selectRadarRange(RadarRangeMode.KM1) }

        compassRadarView.onPeerSelected = { peer ->
            selectedRadarNodeId = peer.nodeId
            val snap = MeshRepository.getSnapshot()
            val list = MeshRepository.getPeersSorted()
            updateRadarTargetHudAndProximity(snap, list)
            compassRadarView.updateRadarData(
                snap.localLatitude,
                snap.localLongitude,
                snap.deviceAzimuthDeg,
                list,
                selectedRadarNodeId
            )
        }

        // 1-tap quick tactical presets
        val presets = listOf(
            "Posisi aman" to false,
            "Tunggu sebentar" to false,
            "Belok di depan" to false,
            "Berhenti cek unit" to false,
            "Butuh bantuan" to true,
            "Kirim Koordinat GPS" to true
        )
        val chipContainer = findViewById<LinearLayout>(R.id.chipQuickContainer)
        for ((label, sendGps) in presets) {
            val btn = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setBackgroundResource(R.drawable.bg_keycap_btn)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ink_primary))
                val padH = (12 * resources.displayMetrics.density).toInt()
                setPadding(padH, 0, padH, 0)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * resources.displayMetrics.density).toInt()
                }
                layoutParams = lp
                setOnClickListener {
                    val snap = MeshRepository.getSnapshot()
                    val msg = if (sendGps && snap.localLatitude == null) {
                        "$label (GNSS belum terkunci)"
                    } else label
                    offGridService?.sendChatMessage(msg, includeGps = sendGps)
                }
            }
            chipContainer.addView(btn)
        }

        btnAttachGpsChat.setOnClickListener {
            attachGpsToChat = !attachGpsToChat
            btnAttachGpsChat.setBackgroundResource(if (attachGpsToChat) R.drawable.bg_accent_btn else R.drawable.bg_keycap_btn)
            (btnAttachGpsChat as? ImageView)?.setColorFilter(
                ContextCompat.getColor(this, if (attachGpsToChat) R.color.ink_on_accent else R.color.ink_primary)
            )
            val note = if (attachGpsToChat) "Koordinat GPS akan disertakan di pesan berikutnya" else "Lampiran koordinat GPS dimatikan"
            Toast.makeText(this, note, Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btnSendChat).setOnClickListener { submitChatFromInput() }
        etChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitChatFromInput()
                true
            } else false
        }

        btnNetworkRescanAll.setOnClickListener {
            offGridService?.triggerQuickNetworkRescan()
            Toast.makeText(this, "Memindai ulang Bluetooth & Wi-Fi Direct...", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnCreateP2pHost).setOnClickListener {
            if (!PermissionHelper.hasWifiDirectPermission(this)) {
                requestMissingPermissionsIfNeeded(initialLaunch = false)
                return@setOnClickListener
            }
            offGridService?.createWifiDirectGroup()
        }
        findViewById<Button>(R.id.btnScanP2pPeers).setOnClickListener {
            if (!PermissionHelper.hasWifiDirectPermission(this)) {
                requestMissingPermissionsIfNeeded(initialLaunch = false)
                return@setOnClickListener
            }
            offGridService?.discoverWifiDirectPeers()
        }
        btnDisconnectP2p.setOnClickListener {
            offGridService?.disconnectWifiDirect()
        }

        btnOpenWifiHotspotSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            } catch (_: Throwable) {
                try {
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                } catch (_: Throwable) {
                }
            }
        }

        btnToggleTechInfo.setOnClickListener {
            showTechDiagnostics = !showTechDiagnostics
            tvNodeDiagnostics.visibility = if (showTechDiagnostics) View.VISIBLE else View.GONE
            btnToggleTechInfo.text = if (showTechDiagnostics) {
                "Sembunyikan Detail Teknis Radio"
            } else {
                "Tampilkan Detail Teknis Radio"
            }
        }

        for ((tab, triple) in tabViews) {
            triple.first.setOnClickListener { selectTab(tab) }
        }
    }

    private fun submitChatFromInput() {
        val text = etChatInput.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        offGridService?.sendChatMessage(text, includeGps = attachGpsToChat)
        etChatInput.setText("")
    }

    private fun selectTab(tab: Tab) {
        currentTab = tab
        if (tab == Tab.CHAT) {
            unreadChatCount = 0
            tabViews[Tab.CHAT]?.third?.text = getString(R.string.tab_chat)
        }
        panelRadio.visibility = if (tab == Tab.RADIO) View.VISIBLE else View.GONE
        panelRadar.visibility = if (tab == Tab.RADAR) View.VISIBLE else View.GONE
        panelChat.visibility = if (tab == Tab.CHAT) View.VISIBLE else View.GONE
        panelNodes.visibility = if (tab == Tab.NODES) View.VISIBLE else View.GONE

        for ((t, triple) in tabViews) {
            val active = (t == tab)
            triple.first.isSelected = active
            val tint = ContextCompat.getColor(this, if (active) R.color.ink_on_accent else R.color.ink_primary)
            triple.second.setColorFilter(tint)
            triple.third.setTextColor(tint)
        }
    }

    private fun showEditIdentityDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_identity, null)
        val snap = MeshRepository.getSnapshot()
        val etCallsign = view.findViewById<EditText>(R.id.etIdentityCallsign)
        val tvChannel = view.findViewById<TextView>(R.id.tvDialogChannelVal)
        var chosenChannel = snap.channelNumber

        etCallsign.setText(snap.localCallsign)
        tvChannel.text = "CH-%02d".format(chosenChannel)

        view.findViewById<Button>(R.id.btnDialogChDown).setOnClickListener {
            chosenChannel = if (chosenChannel <= 1) 20 else chosenChannel - 1
            tvChannel.text = "CH-%02d".format(chosenChannel)
        }
        view.findViewById<Button>(R.id.btnDialogChUp).setOnClickListener {
            chosenChannel = if (chosenChannel >= 20) 1 else chosenChannel + 1
            tvChannel.text = "CH-%02d".format(chosenChannel)
        }

        AlertDialog.Builder(this)
            .setView(view)
            .setPositiveButton("Simpan") { _, _ ->
                val rawName = etCallsign.text?.toString().orEmpty()
                val clean = OffGridProtocol.sanitizeCallsign(rawName, maxLen = 12)
                offGridService?.updateIdentityAndBroadcast(clean, chosenChannel)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun requestMissingPermissionsIfNeeded(initialLaunch: Boolean) {
        val missing = PermissionHelper.getMissingPermissions(this)
        updatePermissionBanner()
        if (missing.isNotEmpty() && (!initialLaunch || Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun updatePermissionBanner() {
        val criticalMissing = !PermissionHelper.hasAudioPermission(this) ||
            !PermissionHelper.hasBleScanPermission(this) ||
            !PermissionHelper.hasWifiDirectPermission(this) ||
            !PermissionHelper.hasLocationPermission(this)
        bannerPermission.visibility = if (criticalMissing) View.VISIBLE else View.GONE
        updateHardwareBanner(MeshRepository.getSnapshot())
    }

    @SuppressLint("MissingPermission")
    private fun openBluetoothEnablePrompt() {
        try {
            if (PermissionHelper.hasBleConnectPermission(this)) {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (_: Throwable) {
            }
        }
    }

    private fun updateHardwareBanner(snapshot: MeshStatusSnapshot) {
        val alreadyLinked = snapshot.connectedTcpSockets > 0 ||
            snapshot.bleGattConnectedCount > 0 ||
            snapshot.hotspotActive ||
            MeshRepository.getPeersSorted().any { it.hasTwoWayLink() }
        if (bannerPermission.visibility == View.VISIBLE || alreadyLinked) {
            bannerHardwareToggle.visibility = View.GONE
            return
        }
        val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val btOn = btAdapter?.isEnabled == true || snapshot.bleEnabled
        val locMgr = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locOn = locMgr != null && LocationManagerCompat.isLocationEnabled(locMgr)

        if (!btOn || !locOn) {
            bannerHardwareToggle.visibility = View.VISIBLE
            btnEnableBtQuick.visibility = if (!btOn && btAdapter != null) View.VISIBLE else View.GONE
            btnEnableLocQuick.visibility = if (!locOn) View.VISIBLE else View.GONE
            tvHardwareToggleInfo.text = when {
                !btOn && !locOn -> "Bluetooth & Lokasi mati. Nyalakan keduanya untuk sambungan otomatis, atau hubungkan ke Hotspot HP."
                !btOn -> "Bluetooth mati. Nyalakan Bluetooth untuk chat & suara tanpa Wi-Fi, atau hubungkan ke Hotspot."
                else -> "Lokasi HP mati: Android memblokir pencarian Bluetooth & Wi-Fi Direct bila Lokasi mati."
            }
        } else {
            bannerHardwareToggle.visibility = View.GONE
        }
    }

    private fun checkSilentOtaBadge() {
        if (!AppUpdateManager.shouldRunSilentLaunchCheck(this)) return
        Executors.newSingleThreadExecutor().execute {
            val outcome = AppUpdateManager.checkForUpdate(
                this,
                AppUpdateManager.isIncludePreRelease(this)
            )
            if (outcome is AppUpdateManager.CheckOutcome.Success && outcome.release.updateAvailable) {
                uiHandler.post {
                    viewUpdateBadge.visibility = View.VISIBLE
                }
            }
        }
    }
}
