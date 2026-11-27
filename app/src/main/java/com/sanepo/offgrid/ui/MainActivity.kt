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
import com.sanepo.offgrid.model.MeshStatusSnapshot
import com.sanepo.offgrid.model.PeerNode
import com.sanepo.offgrid.model.WifiDirectDiscoveredPeer
import com.sanepo.offgrid.service.OffGridService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), MeshRepository.Observer {

    private enum class Tab { RADIO, RADAR, CHAT, NODES }

    private val uiHandler = Handler(Looper.getMainLooper())
    private var currentTab = Tab.RADIO
    private var attachGpsToChat = false
    private var selectedRadarNodeId: String? = null
    private var unreadChatCount = 0
    private var lastMessageCount = 0
    private var hardwarePttActive = false

    private var offGridService: OffGridService? = null
    private var serviceBound = false

    private val chatAdapter = ChatMessageAdapter()
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
    private lateinit var waveformMeter: WaveformMeterView
    private lateinit var btnPttDome: View
    private lateinit var ivPttIcon: ImageView
    private lateinit var tvPttDomeLabel: TextView
    private lateinit var btnPttLatch: View
    private lateinit var tvLatchLabel: TextView
    private lateinit var btnSosHold: View
    private lateinit var tvSosBtnLabel: TextView
    private lateinit var progressSosHold: android.widget.ProgressBar

    private lateinit var tvRadarTargetTitle: TextView
    private lateinit var tvRadarLocalGps: TextView
    private lateinit var compassRadarView: CompassRadarView
    private lateinit var tvRadarEmptyState: View

    private lateinit var rvChatMessages: androidx.recyclerview.widget.RecyclerView
    private lateinit var chatEmptyState: View
    private lateinit var tvChatEmptyMsg: TextView
    private lateinit var btnAttachGpsChat: View
    private lateinit var etChatInput: EditText

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
        tvHeaderCallsign.text = "${snapshot.localCallsign} | CH-%02d".format(snapshot.channelNumber)
        tvHeaderSummary.text = "${peers.size} unit terdeteksi | ${snapshot.wifiDirectRole}"

        // SOS alert banner
        val remoteSos = snapshot.remoteSosNodes
        when {
            snapshot.localSosActive -> {
                bannerSosAlert.visibility = View.VISIBLE
                tvSosBannerText.text = "DARURAT SOS LOKAL AKTIF (BLE & Wi-Fi memancar)"
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
        val sameChannelCount = peers.count { it.channelNumber == snapshot.channelNumber }
        tvRadioPeerCountBadge.text = "$sameChannelCount di CH-%02d (${peers.size} total)".format(snapshot.channelNumber)

        val rxActive = snapshot.activeTalkerCallsign != null && !snapshot.isLocalTransmitting
        waveformMeter.updateAudioState(
            levelNormalized = if (snapshot.isLocalTransmitting) snapshot.micLevelNormalized else snapshot.rxLevelNormalized,
            txActive = snapshot.isLocalTransmitting,
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
            snapshot.isLocalTransmitting -> "MENGIRIM SUARA..."
            else -> "TAHAN BICARA"
        }

        tvRadioFloorState.text = when {
            snapshot.isLocalTransmitting -> "MEMANCAR SUARA ke CH-%02d (Roger Beep aktif)".format(snapshot.channelNumber)
            snapshot.activeTalkerCallsign != null -> "MENERIMA SUARA DARI: ${snapshot.activeTalkerCallsign}"
            snapshot.lastError != null -> snapshot.lastError
            else -> "SIAGA DENGAR : Tahan PTT atau tekan Kunci Mic untuk berbicara"
        }

        val btLinkText = when {
            snapshot.bleGattConnectedCount > 0 -> "BT+ (${snapshot.bleGattConnectedCount})"
            snapshot.bleAdvertising || snapshot.bleScanning -> "BLE Aktif"
            snapshot.bleEnabled -> "Siap"
            else -> "Mati"
        }
        val lanTag = snapshot.localLanIps.firstOrNull() ?: "-"
        tvRadioTransportSummary.text = "$btLinkText | P2P: ${snapshot.wifiDirectRole} | IP: $lanTag"
        updateHardwareBanner(snapshot)

        tvLatchLabel.text = if (snapshot.isPttLatched) "Kunci Mic: AKTIF" else "Kunci Mic: MATI"
        btnPttLatch.setBackgroundResource(if (snapshot.isPttLatched) R.drawable.bg_accent_btn else R.drawable.bg_keycap_btn)
        tvLatchLabel.setTextColor(ContextCompat.getColor(this, if (snapshot.isPttLatched) R.color.ink_on_accent else R.color.ink_primary))

        tvSosBtnLabel.text = if (snapshot.localSosActive) {
            "KETUK UNTUK MATIKAN SOS DARURAT"
        } else {
            "TAHAN 2 DETIK : PANCARKAN DARURAT SOS"
        }

        // Tab 2: Radar Compass
        tvRadarLocalGps.text = snapshot.gpsStatusText
        compassRadarView.updateRadarData(
            localLatitude = snapshot.localLatitude,
            localLongitude = snapshot.localLongitude,
            azimuthDeg = snapshot.deviceAzimuthDeg,
            peerList = peers,
            selectedId = selectedRadarNodeId
        )
        radarPeerAdapter.submitList(peers, snapshot.localLatitude, snapshot.localLongitude)
        tvRadarEmptyState.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
        updateRadarTargetHud(snapshot, peers)

        // Tab 3: Chat
        if (messages.size > lastMessageCount) {
            val delta = messages.size - lastMessageCount
            if (currentTab != Tab.CHAT && lastMessageCount > 0) {
                unreadChatCount += delta
            }
            lastMessageCount = messages.size
            chatAdapter.submitList(messages)
            if (messages.isNotEmpty()) {
                rvChatMessages.scrollToPosition(messages.size - 1)
            }
        } else if (messages.size != chatAdapter.itemCount) {
            chatAdapter.submitList(messages)
        }
        chatEmptyState.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        tvChatEmptyMsg.text = "Belum ada pesan di CH-%02d. Gunakan pesan cepat di bawah atau ketik pesan baru.".format(snapshot.channelNumber)
        tabViews[Tab.CHAT]?.third?.text = if (unreadChatCount > 0) "Chat ($unreadChatCount)" else getString(R.string.tab_chat)

        // Tab 4: Nodes & Network
        val ipText = if (snapshot.localLanIps.isEmpty()) "Tidak ada Wi-Fi/Hotspot aktif" else snapshot.localLanIps.joinToString(", ")
        tvNodeDiagnostics.text = buildString {
            appendLine("Node ID: ${snapshot.localNodeId} (${snapshot.localCallsign})")
            appendLine("BLE: ${snapshot.bleStatusDetail} | GATT Aktif: ${snapshot.bleGattConnectedCount}")
            appendLine("Wi-Fi Direct: ${snapshot.wifiDirectRole} | ${snapshot.wifiDirectDetail}")
            appendLine("IP Lokal: $ipText")
            append("Socket TCP Terhubung: ${snapshot.connectedTcpSockets} | Baterai: ${snapshot.batteryPercent}%")
        }
        val inGroup = snapshot.wifiDirectRole.startsWith("Host") || snapshot.wifiDirectRole.startsWith("Klien")
        btnDisconnectP2p.visibility = if (inGroup) View.VISIBLE else View.GONE

        p2pDiscoveredAdapter.submitList(wifiDirectPeers)
        tvP2pEmpty.visibility = if (wifiDirectPeers.isEmpty()) View.VISIBLE else View.GONE

        allPeerAdapter.submitList(peers, snapshot.localLatitude, snapshot.localLongitude)
        tvMeshEmpty.visibility = if (peers.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateRadarTargetHud(snapshot: MeshStatusSnapshot, peers: List<PeerNode>) {
        val target = peers.firstOrNull { it.nodeId == selectedRadarNodeId } ?: peers.firstOrNull()
        if (target == null) {
            tvRadarTargetTitle.text = "Belum ada target di radar (${snapshot.deviceAzimuthDeg.toInt()}° arah hadap)"
            return
        }
        val lat = snapshot.localLatitude
        val lon = snapshot.localLongitude
        if (lat != null && lon != null && target.latitude != null && target.longitude != null) {
            val meters = GeoMath.haversineDistanceMeters(lat, lon, target.latitude, target.longitude)
            val bearing = GeoMath.initialBearingDegrees(lat, lon, target.latitude, target.longitude)
            val card = GeoMath.cardinalDirectionId(bearing)
            tvRadarTargetTitle.text = "TARGET: ${target.callsign} | ARAH: ${bearing.toInt()}° ($card) | JARAK: ${GeoMath.formatDistanceCompact(meters)}"
        } else if (target.estimatedBleMeters != null) {
            tvRadarTargetTitle.text = "TARGET: ${target.callsign} | RSSI BLE ~${GeoMath.formatDistanceCompact(target.estimatedBleMeters)} (tanpa sudut GPS)"
        } else {
            tvRadarTargetTitle.text = "TARGET: ${target.callsign} | Terhubung via P2P/LAN (tanpa koordinat GPS)"
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
        waveformMeter = findViewById(R.id.waveformMeter)
        btnPttDome = findViewById(R.id.btnPttDome)
        ivPttIcon = findViewById(R.id.ivPttIcon)
        tvPttDomeLabel = findViewById(R.id.tvPttDomeLabel)
        btnPttLatch = findViewById(R.id.btnPttLatch)
        tvLatchLabel = findViewById(R.id.tvLatchLabel)
        btnSosHold = findViewById(R.id.btnSosHold)
        tvSosBtnLabel = findViewById(R.id.tvSosBtnLabel)
        progressSosHold = findViewById(R.id.progressSosHold)

        tvRadarTargetTitle = findViewById(R.id.tvRadarTargetTitle)
        tvRadarLocalGps = findViewById(R.id.tvRadarLocalGps)
        compassRadarView = findViewById(R.id.compassRadarView)
        tvRadarEmptyState = findViewById(R.id.tvRadarEmptyState)

        rvChatMessages = findViewById(R.id.rvChatMessages)
        chatEmptyState = findViewById(R.id.chatEmptyState)
        tvChatEmptyMsg = findViewById(R.id.tvChatEmptyMsg)
        btnAttachGpsChat = findViewById(R.id.btnAttachGpsChat)
        etChatInput = findViewById(R.id.etChatInput)

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
            updateRadarTargetHud(MeshRepository.getSnapshot(), MeshRepository.getPeersSorted())
            compassRadarView.updateRadarData(
                MeshRepository.getSnapshot().localLatitude,
                MeshRepository.getSnapshot().localLongitude,
                MeshRepository.getSnapshot().deviceAzimuthDeg,
                MeshRepository.getPeersSorted(),
                selectedRadarNodeId
            )
        }
        radarPeerAdapter = PeerNodeAdapter(onPeerPicked)
        allPeerAdapter = PeerNodeAdapter(onPeerPicked)
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

        // Tactile Push-To-Talk Dome (Touch + Keyboard Enter/Space accessibility)
        btnPttDome.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (!PermissionHelper.hasAudioPermission(this)) {
                        requestMissingPermissionsIfNeeded(initialLaunch = false)
                    } else if (!MeshRepository.getSnapshot().isPttLatched) {
                        offGridService?.startPttTransmit()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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

        // Hold 2 sec SOS button (prevent accidental tap)
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

        compassRadarView.onPeerSelected = { peer ->
            selectedRadarNodeId = peer.nodeId
            updateRadarTargetHud(MeshRepository.getSnapshot(), MeshRepository.getPeersSorted())
            compassRadarView.updateRadarData(
                MeshRepository.getSnapshot().localLatitude,
                MeshRepository.getSnapshot().localLongitude,
                MeshRepository.getSnapshot().deviceAzimuthDeg,
                MeshRepository.getPeersSorted(),
                selectedRadarNodeId
            )
        }

        // 1-tap quick chat presets
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
        if (bannerPermission.visibility == View.VISIBLE) {
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
                !btOn && !locOn -> "Bluetooth & Lokasi HP mati. Nyalakan keduanya agar terhubung otomatis tanpa Wi-Fi."
                !btOn -> "Bluetooth mati. Nyalakan Bluetooth untuk koneksi langsung tanpa Wi-Fi."
                else -> "Lokasi (GPS) HP mati. Android mewajibkan Lokasi aktif untuk pindai Bluetooth & Wi-Fi Direct."
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
