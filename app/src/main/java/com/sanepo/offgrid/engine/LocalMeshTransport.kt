package com.sanepo.offgrid.engine

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.sanepo.offgrid.model.TransportLinkType
import com.sanepo.offgrid.model.WifiDirectDiscoveredPeer
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LocalMeshTransport(
    private val context: Context,
    private val onControlJsonReceived: (json: String, remoteIp: String, sourcePeerKey: String?) -> Unit,
    private val onAudioFrameReceived: (frame: OffGridProtocol.AudioFramePacket, rawBytes: ByteArray, sourcePeerKey: String?) -> Unit,
    private val onPeerLinkReady: (peerNodeId: String?, sourcePeerKey: String) -> Unit = { _, _ -> }
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val audioTxExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var p2pChannel: WifiP2pManager.Channel? = null

    private var running = false
    private var receiverRegistered = false
    private var localServiceRegistered = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var tcpServerSocket: ServerSocket? = null
    private var udpDiscoverySocket: DatagramSocket? = null
    private var udpAudioSocket: DatagramSocket? = null

    private val clientHandlers = ConcurrentHashMap<String, ClientConnection>()
    private val peerUdpTargets = ConcurrentHashMap<String, String>() // direct nodeId -> ipAddress
    private val cachedPeerInetAddrs = ConcurrentHashMap<String, InetAddress>()
    private val udpAudioVerifiedNodes: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var cachedBroadcastAddrs: List<InetAddress> = emptyList()
    private val txtMetadataByMac = ConcurrentHashMap<String, Map<String, String>>()
    private val latestP2pDevices = ConcurrentHashMap<String, WifiP2pDevice>()
    private var p2pServiceInfo: WifiP2pDnsSdServiceInfo? = null
    private var p2pServiceRequest: WifiP2pDnsSdServiceRequest? = null

    @Volatile
    private var isP2pGroupOwner = false

    @Volatile
    private var currentP2pGroupOwnerIp: String? = null

    private val p2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    MeshRepository.updateSnapshot(forceImmediate = true) {
                        copy(
                            wifiEnabled = enabled,
                            wifiDirectDetail = if (enabled) "Wi-Fi Direct siap" else "Wi-Fi mati: nyalakan Wi-Fi atau gunakan Hotspot/Bluetooth"
                        )
                    }
                    if (enabled && running) {
                        registerDnsSdService()
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    requestWifiDirectPeers()
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val ch = p2pChannel ?: return
                    p2pManager?.requestConnectionInfo(ch) { info ->
                        handleWifiDirectConnectionInfo(info)
                    }
                }
            }
        }
    }

    fun start() {
        if (running) return
        running = true

        val p2pSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) && p2pManager != null
        if (p2pSupported) {
            p2pChannel = p2pManager?.initialize(context, Looper.getMainLooper(), null)
            registerP2pReceiver()
        }

        try {
            multicastLock = wifiManager?.createMulticastLock("SanepoOffGridLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager?.createWifiLock(lockMode, "SanepoWifiLatencyLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {
        }

        cachedBroadcastAddrs = enumerateBroadcastAddresses()
        val (ips, hotspotOn) = inspectLocalInterfaces()
        MeshRepository.updateSnapshot(forceImmediate = true) {
            copy(
                wifiDirectSupported = p2pSupported,
                wifiEnabled = wifiManager?.isWifiEnabled == true,
                hotspotActive = hotspotOn,
                localLanIps = ips
            )
        }

        startTcpServerLoop()
        startUdpDiscoveryLoop()
        startUdpAudioLoop()
        startBeaconHeartbeatLoop()

        if (p2pSupported) {
            registerDnsSdService(discoverAfter = true)
        }
    }

    fun stop() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        unregisterP2pReceiver()
        unregisterDnsSdService()
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        try {
            tcpServerSocket?.close()
        } catch (_: Throwable) {
        }
        try {
            udpDiscoverySocket?.close()
        } catch (_: Throwable) {
        }
        try {
            udpAudioSocket?.close()
        } catch (_: Throwable) {
        }
        clientHandlers.values.forEach { it.closeQuietly(updateUi = false) }
        clientHandlers.clear()
        peerUdpTargets.clear()
        cachedPeerInetAddrs.clear()
        udpAudioVerifiedNodes.clear()
        MeshRepository.updateSnapshot(forceImmediate = true) {
            copy(connectedTcpSockets = 0, wifiDirectRole = "Nonaktif")
        }
    }

    @SuppressLint("MissingPermission")
    fun createWifiDirectGroup() {
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        if (!PermissionHelper.hasWifiDirectPermission(context)) {
            updateWifiDirectDetail("Izin Wi-Fi Sekitar / Lokasi belum diberikan")
            return
        }
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                startCreateGroupCall(mgr, ch)
            }

            override fun onFailure(reason: Int) {
                startCreateGroupCall(mgr, ch)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startCreateGroupCall(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateWifiDirectDetail("Rombongan dibuat! Teman sekarang bisa menekan Cari & Gabung.")
            }

            override fun onFailure(reason: Int) {
                updateWifiDirectDetail("Gagal membuat rombongan (kode $reason). Coba gunakan Mode Hotspot HP.")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun discoverPeersAndServices() {
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        if (!PermissionHelper.hasWifiDirectPermission(context)) {
            updateWifiDirectDetail("Izin Wi-Fi Sekitar / Lokasi dibutuhkan untuk mencari rombongan")
            return
        }

        mgr.setDnsSdResponseListeners(
            ch,
            { instanceName, _, srcDevice ->
                if (srcDevice != null && instanceName.contains("Sanepo", ignoreCase = true)) {
                    latestP2pDevices[srcDevice.deviceAddress] = srcDevice
                    publishWifiDirectPeerList()
                }
            },
            { _, txtRecord, srcDevice ->
                if (srcDevice != null && txtRecord != null) {
                    txtMetadataByMac[srcDevice.deviceAddress] = txtRecord
                    latestP2pDevices[srcDevice.deviceAddress] = srcDevice
                    publishWifiDirectPeerList()
                }
            }
        )

        val request = p2pServiceRequest ?: WifiP2pDnsSdServiceRequest.newInstance().also {
            p2pServiceRequest = it
        }
        mgr.clearServiceRequests(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                mgr.addServiceRequest(ch, request, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        mgr.discoverServices(ch, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() {
                                updateWifiDirectDetail("Mencari HP Sanepo lain di sekitar...")
                            }

                            override fun onFailure(reason: Int) {
                                mgr.discoverPeers(ch, null)
                            }
                        })
                    }

                    override fun onFailure(reason: Int) {
                        mgr.discoverPeers(ch, null)
                    }
                })
            }

            override fun onFailure(reason: Int) {
                mgr.discoverPeers(ch, null)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToWifiDirectPeer(deviceAddress: String) {
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        if (!PermissionHelper.hasWifiDirectPermission(context)) {
            updateWifiDirectDetail("Izin Wi-Fi Sekitar / Lokasi belum diberikan")
            return
        }
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            groupOwnerIntent = 0
        }
        updateWifiDirectDetail("Menghubungkan ke rombongan teman...")
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateWifiDirectDetail("Permintaan terkirim. Ketuk Terima di HP Host bila muncul konfirmasi.")
            }

            override fun onFailure(reason: Int) {
                updateWifiDirectDetail("Gagal gabung (kode $reason). Pastikan teman sudah menekan Buat Rombongan.")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun disconnectWifiDirect() {
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        isP2pGroupOwner = false
        currentP2pGroupOwnerIp = null
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                MeshRepository.updateSnapshot(forceImmediate = true) {
                    copy(wifiDirectRole = "Siaga", wifiDirectDetail = "Sudah keluar dari rombongan")
                }
            }

            override fun onFailure(reason: Int) {
                MeshRepository.updateSnapshot(forceImmediate = true) {
                    copy(wifiDirectRole = "Siaga", wifiDirectDetail = "Wi-Fi Direct siaga")
                }
            }
        })
    }

    fun sendControlToAll(
        envelope: OffGridProtocol.ControlEnvelope,
        excludeTcpKey: String? = null
    ) {
        val json = OffGridProtocol.encodeControlJson(envelope)
        executor.execute {
            for ((key, client) in clientHandlers) {
                if (key == excludeTcpKey || client.isClosed()) continue
                client.sendLine(json)
            }
            if (excludeTcpKey == null && (
                envelope is OffGridProtocol.ControlEnvelope.Chat ||
                    envelope is OffGridProtocol.ControlEnvelope.ChatAck ||
                    envelope is OffGridProtocol.ControlEnvelope.SosSignal ||
                    envelope is OffGridProtocol.ControlEnvelope.PttFloor ||
                    envelope is OffGridProtocol.ControlEnvelope.Ping
                )
            ) {
                broadcastUdpControl(json)
            }
        }
    }

    fun hasNetworkPeers(): Boolean =
        clientHandlers.values.any { !it.isClosed() } || udpAudioVerifiedNodes.isNotEmpty()

    fun sendAudioFrameToPeers(
        packetBytes: ByteArray,
        excludeTcpKey: String? = null
    ) {
        audioTxExecutor.execute {
            val socket = udpAudioSocket
            val targetAddrs = cachedPeerInetAddrs.values
            if (socket != null) {
                for (addr in targetAddrs) {
                    sendUdpPacketSafe(socket, packetBytes, addr, OffGridProtocol.UDP_AUDIO_PORT)
                }
                if (targetAddrs.isEmpty()) {
                    for (bcast in cachedBroadcastAddrs) {
                        sendUdpPacketSafe(socket, packetBytes, bcast, OffGridProtocol.UDP_AUDIO_PORT)
                    }
                }
            }
            // Also feed active TCP peers whose UDP path isn't verified OR when relaying multi-hop
            val needTcpFallback = excludeTcpKey != null ||
                (clientHandlers.isNotEmpty() && udpAudioVerifiedNodes.size < clientHandlers.size)
            if (needTcpFallback) {
                val tcpJson = OffGridProtocol.encodeControlJson(OffGridProtocol.ControlEnvelope.TcpAudio(packetBytes))
                for ((key, client) in clientHandlers) {
                    if (key == excludeTcpKey || client.isClosed()) continue
                    if (excludeTcpKey == null && client.remoteNodeId != null && udpAudioVerifiedNodes.contains(client.remoteNodeId)) {
                        continue
                    }
                    client.sendLine(tcpJson, isAudio = true)
                }
            }
        }
    }

    fun onHelloDiscoveredOverUdpOrTcp(
        env: OffGridProtocol.ControlEnvelope.Hello,
        remoteIp: String,
        sourceKey: String?
    ) {
        val localId = MeshRepository.getSnapshot().localNodeId
        if (env.nodeId == localId) return

        // Tag direct TCP handler's remoteNodeId when Hello hopCount == 0
        if (env.hopCount == 0 && sourceKey != null && sourceKey.startsWith("TCP:")) {
            val tcpKey = sourceKey.removePrefix("TCP:")
            clientHandlers[tcpKey]?.let { conn ->
                val wasUnknown = conn.remoteNodeId == null
                conn.remoteNodeId = env.nodeId
                if (wasUnknown) {
                    onPeerLinkReady(env.nodeId, sourceKey)
                }
            }
        }

        if (env.hopCount == 0) {
            rememberPeerUdpTarget(env.nodeId, remoteIp)
            val alreadyTcpConnected = clientHandlers.values.any {
                !it.isClosed() && (it.remoteIp == remoteIp || it.remoteNodeId == env.nodeId)
            }
            if (sourceKey == null && !alreadyTcpConnected) {
                if (localId > env.nodeId || isP2pGroupOwner) {
                    connectTcpToIpIfNeeded(remoteIp)
                } else {
                    // Lower ID also initiates after 900ms if higher ID failed to open incoming socket
                    mainHandler.postDelayed({
                        val stillMissing = clientHandlers.values.none {
                            !it.isClosed() && (it.remoteIp == remoteIp || it.remoteNodeId == env.nodeId)
                        }
                        if (running && stillMissing) {
                            connectTcpToIpIfNeeded(remoteIp)
                        }
                    }, 900L)
                }
            }
        }
    }

    fun refreshLocalServiceIdentity() {
        if (!running || p2pManager == null) return
        unregisterDnsSdService()
        registerDnsSdService()
        sendControlToAll(buildLocalHelloEnvelope())
    }

    private fun startTcpServerLoop() {
        executor.execute {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(OffGridProtocol.TCP_MESH_PORT))
                }
                tcpServerSocket = server
                while (running && !server.isClosed) {
                    val sock = server.accept()
                    val remoteIp = sock.inetAddress.hostAddress ?: continue
                    registerClientSocket(sock, remoteIp)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun connectTcpToIpIfNeeded(targetIp: String) {
        val (myIps, _) = inspectLocalInterfaces()
        if (myIps.contains(targetIp)) return
        if (clientHandlers.values.any { it.remoteIp == targetIp && !it.isClosed() }) return
        executor.execute {
            try {
                if (clientHandlers.values.any { it.remoteIp == targetIp && !it.isClosed() }) return@execute
                val sock = Socket()
                // Force socket onto local Wi-Fi Network when connecting to non-P2P LAN/Hotspot so 4G data doesn't hijack it
                if (!targetIp.startsWith("192.168.49.")) {
                    findLocalWifiNetwork()?.let { wifiNet ->
                        try {
                            wifiNet.bindSocket(sock)
                        } catch (_: Throwable) {
                        }
                    }
                }
                sock.connect(InetSocketAddress(targetIp, OffGridProtocol.TCP_MESH_PORT), 2800)
                registerClientSocket(sock, targetIp)
            } catch (_: Throwable) {
            }
        }
    }

    private fun registerClientSocket(socket: Socket, remoteIp: String) {
        val key = "$remoteIp:${socket.port}"
        val handler = ClientConnection(key, remoteIp, socket)
        clientHandlers[key] = handler
        updateConnectedTcpCount()
        handler.sendLine(OffGridProtocol.encodeControlJson(buildLocalHelloEnvelope()))
        handler.startReading()
    }

    private fun startUdpDiscoveryLoop() {
        executor.execute {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(OffGridProtocol.UDP_DISCOVERY_PORT))
                }
                udpDiscoverySocket = sock
                val buf = ByteArray(16_384)
                while (running && !sock.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val remoteIp = pkt.address?.hostAddress ?: continue
                    val (myIps, _) = inspectLocalInterfaces()
                    if (myIps.contains(remoteIp)) continue
                    val raw = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                    onControlJsonReceived(raw, remoteIp, null)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun startUdpAudioLoop() {
        executor.execute {
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(OffGridProtocol.UDP_AUDIO_PORT))
                }
                udpAudioSocket = sock
                val buf = ByteArray(4096)
                while (running && !sock.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val frame = OffGridProtocol.decodeAudioFrame(pkt.data, pkt.length) ?: continue
                    if (frame.senderNodeId == MeshRepository.getSnapshot().localNodeId) continue
                    udpAudioVerifiedNodes.add(frame.senderNodeId)
                    if (frame.type == OffGridProtocol.AUDIO_TYPE_PING) continue

                    val rawCopy = pkt.data.copyOf(pkt.length)
                    val remoteIp = pkt.address?.hostAddress
                    val matchingKey = clientHandlers.entries
                        .firstOrNull { it.value.remoteIp == remoteIp }?.key?.let { "TCP:$it" }
                    onAudioFrameReceived(frame, rawCopy, matchingKey)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun startBeaconHeartbeatLoop() {
        executor.execute {
            var cycle = 0
            while (running) {
                try {
                    val now = System.currentTimeMillis()
                    // 1. Clean up dead half-open TCP connections that missed 4 heartbeats (12s)
                    for ((key, client) in clientHandlers) {
                        if (client.isClosed() || now - client.lastRxMs > 12_500L) {
                            val deadNodeId = client.remoteNodeId
                            client.closeQuietly(updateUi = true)
                            if (deadNodeId != null) {
                                udpAudioVerifiedNodes.remove(deadNodeId)
                                val hasAnyLeft = clientHandlers.values.any { !it.isClosed() && it.remoteNodeId == deadNodeId }
                                if (!hasAnyLeft) {
                                    MeshRepository.clearPeerLinkType(deadNodeId, TransportLinkType.LOCAL_LAN)
                                    MeshRepository.clearPeerLinkType(deadNodeId, TransportLinkType.WIFI_DIRECT)
                                }
                            }
                        } else {
                            // Deduplicate redundant dual sockets to the exact same remoteIp
                            val dup = clientHandlers.values.any { other ->
                                other !== client && !other.isClosed() && other.remoteIp == client.remoteIp &&
                                    other.key > key
                            }
                            if (dup && client.createdAtMs + 2500L < now) {
                                client.closeQuietly(updateUi = true)
                            }
                        }
                    }

                    // 2. Retry Wi-Fi Direct TCP connection to Group Owner if interface wasn't ready initially
                    val ownerIp = currentP2pGroupOwnerIp
                    if (!isP2pGroupOwner && !ownerIp.isNullOrBlank()) {
                        val hasOwnerConn = clientHandlers.values.any { !it.isClosed() && it.remoteIp == ownerIp }
                        if (!hasOwnerConn) {
                            connectTcpToIpIfNeeded(ownerIp)
                        }
                    }

                    // 3. Refresh local interfaces, broadcast Hello, and probe UDP audio path
                    cachedBroadcastAddrs = enumerateBroadcastAddresses()
                    val hello = buildLocalHelloEnvelope()
                    val json = OffGridProtocol.encodeControlJson(hello)
                    broadcastUdpControl(json)
                    for (client in clientHandlers.values) {
                        if (!client.isClosed()) client.sendLine(json)
                    }

                    val pingBytes = OffGridProtocol.encodeRelayAudioFrame(
                        type = OffGridProtocol.AUDIO_TYPE_PING,
                        channelNumber = hello.channelNumber,
                        senderNodeId = hello.nodeId,
                        sequence = 0,
                        ttl = 1,
                        pcmBytes = ByteArray(0)
                    )
                    udpAudioSocket?.let { uSock ->
                        for (addr in cachedPeerInetAddrs.values) {
                            sendUdpPacketSafe(uSock, pingBytes, addr, OffGridProtocol.UDP_AUDIO_PORT)
                        }
                    }

                    val wifiOn = wifiManager?.isWifiEnabled == true
                    val (lans, hotspotOn) = inspectLocalInterfaces()
                    MeshRepository.updateSnapshot(forceImmediate = false) {
                        copy(
                            wifiEnabled = wifiOn,
                            hotspotActive = hotspotOn,
                            localLanIps = lans
                        )
                    }
                    cycle++
                    if (wifiOn && cycle % 6 == 0 && clientHandlers.isEmpty() && PermissionHelper.hasWifiDirectPermission(context)) {
                        discoverPeersAndServices()
                    }
                    Thread.sleep(2500L)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun broadcastUdpControl(json: String) {
        val sock = udpDiscoverySocket ?: return
        val bytes = json.toByteArray(Charsets.UTF_8)
        for (bcast in enumerateBroadcastAddresses()) {
            sendUdpPacketSafe(sock, bytes, bcast, OffGridProtocol.UDP_DISCOVERY_PORT)
        }
        for (ip in peerUdpTargets.values.toSet()) {
            try {
                val addr = InetAddress.getByName(ip)
                sendUdpPacketSafe(sock, bytes, addr, OffGridProtocol.UDP_DISCOVERY_PORT)
            } catch (_: Throwable) {
            }
        }
    }

    private fun sendUdpPacketSafe(
        defaultSocket: DatagramSocket,
        bytes: ByteArray,
        target: InetAddress,
        port: Int
    ) {
        try {
            defaultSocket.send(DatagramPacket(bytes, bytes.size, target, port))
        } catch (_: Throwable) {
        }
        // When mobile 4G is enabled alongside an offline Wi-Fi Hotspot, also transmit via Wi-Fi-bound DatagramSocket
        val ipStr = target.hostAddress ?: return
        if (!ipStr.startsWith("192.168.49.")) {
            val wifiNet = findLocalWifiNetwork()
            if (wifiNet != null && hasCellularDefaultNetwork()) {
                var boundSock: DatagramSocket? = null
                try {
                    boundSock = DatagramSocket()
                    wifiNet.bindSocket(boundSock)
                    boundSock.broadcast = true
                    boundSock.send(DatagramPacket(bytes, bytes.size, target, port))
                } catch (_: Throwable) {
                } finally {
                    try {
                        boundSock?.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun findLocalWifiNetwork(): Network? {
        val cm = connectivityManager ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            cm.allNetworks.firstOrNull { net ->
                val caps = cm.getNetworkCapabilities(net)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun hasCellularDefaultNetwork(): Boolean {
        val cm = connectivityManager ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        } catch (_: Throwable) {
            false
        }
    }

    private fun handleWifiDirectConnectionInfo(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) {
            isP2pGroupOwner = false
            currentP2pGroupOwnerIp = null
            MeshRepository.updateSnapshot(forceImmediate = true) {
                copy(wifiDirectRole = "Siaga", wifiDirectDetail = "Wi-Fi Direct siaga")
            }
            return
        }
        isP2pGroupOwner = info.isGroupOwner
        val ownerIp = info.groupOwnerAddress?.hostAddress
        currentP2pGroupOwnerIp = ownerIp
        if (info.isGroupOwner) {
            MeshRepository.updateSnapshot(forceImmediate = true) {
                copy(
                    wifiDirectRole = "Ketua Rombongan",
                    wifiDirectDetail = "Rombongan aktif! Teman bisa menekan Cari & Gabung ke HP ini"
                )
            }
        } else {
            MeshRepository.updateSnapshot(forceImmediate = true) {
                copy(
                    wifiDirectRole = "Anggota Rombongan",
                    wifiDirectDetail = "Tersambung ke rombongan (${ownerIp ?: "-"})"
                )
            }
            if (!ownerIp.isNullOrBlank()) {
                val retryDelays = longArrayOf(0L, 600L, 1600L, 3200L)
                for (delayMs in retryDelays) {
                    mainHandler.postDelayed({
                        if (running && currentP2pGroupOwnerIp == ownerIp) {
                            connectTcpToIpIfNeeded(ownerIp)
                        }
                    }, delayMs)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestWifiDirectPeers() {
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        if (!PermissionHelper.hasWifiDirectPermission(context)) return
        mgr.requestPeers(ch) { peerList ->
            latestP2pDevices.clear()
            peerList.deviceList?.forEach { dev ->
                latestP2pDevices[dev.deviceAddress] = dev
            }
            publishWifiDirectPeerList()
        }
    }

    private fun publishWifiDirectPeerList() {
        val list = latestP2pDevices.values.map { dev ->
            val txt = txtMetadataByMac[dev.deviceAddress]
            WifiDirectDiscoveredPeer(
                deviceAddress = dev.deviceAddress,
                deviceName = dev.deviceName.ifBlank { "Perangkat Android" },
                callsign = txt?.get("cs"),
                channelNumber = txt?.get("ch")?.toIntOrNull(),
                statusCode = dev.status,
                isGroupOwner = dev.isGroupOwner
            )
        }
        MeshRepository.replaceWifiDirectDiscoveredPeers(list)
    }

    private fun rememberPeerUdpTarget(nodeId: String, remoteIp: String) {
        if (peerUdpTargets[nodeId] == remoteIp && cachedPeerInetAddrs.containsKey(nodeId)) return
        peerUdpTargets[nodeId] = remoteIp
        try {
            cachedPeerInetAddrs[nodeId] = InetAddress.getByName(remoteIp)
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerDnsSdService(discoverAfter: Boolean = false) {
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        if (!PermissionHelper.hasWifiDirectPermission(context)) return
        val snap = MeshRepository.getSnapshot()
        val record = mapOf(
            "id" to snap.localNodeId,
            "cs" to snap.localCallsign,
            "ch" to snap.channelNumber.toString()
        )
        val info = WifiP2pDnsSdServiceInfo.newInstance(
            "Sanepo-${snap.localCallsign}",
            OffGridProtocol.SERVICE_TYPE,
            record
        )
        p2pServiceInfo = info
        mgr.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                mgr.addLocalService(ch, info, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        localServiceRegistered = true
                        if (discoverAfter && running) discoverPeersAndServices()
                    }

                    override fun onFailure(reason: Int) {
                        localServiceRegistered = false
                        if (discoverAfter && running) discoverPeersAndServices()
                    }
                })
            }

            override fun onFailure(reason: Int) {
                mgr.addLocalService(ch, info, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (discoverAfter && running) discoverPeersAndServices()
                    }

                    override fun onFailure(reason: Int) {
                        if (discoverAfter && running) discoverPeersAndServices()
                    }
                })
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun unregisterDnsSdService() {
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        if (!localServiceRegistered || !PermissionHelper.hasWifiDirectPermission(context)) return
        localServiceRegistered = false
        try {
            p2pServiceInfo?.let { mgr.removeLocalService(ch, it, null) }
            p2pServiceRequest?.let { mgr.removeServiceRequest(ch, it, null) }
        } catch (_: Throwable) {
        }
    }

    private fun registerP2pReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, p2pReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterP2pReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(p2pReceiver)
        } catch (_: Throwable) {
        }
        receiverRegistered = false
    }

    private fun buildLocalHelloEnvelope(): OffGridProtocol.ControlEnvelope.Hello {
        val snap = MeshRepository.getSnapshot()
        return OffGridProtocol.ControlEnvelope.Hello(
            nodeId = snap.localNodeId,
            callsign = snap.localCallsign,
            channelNumber = snap.channelNumber,
            tcpPort = OffGridProtocol.TCP_MESH_PORT,
            batteryPercent = snap.batteryPercent,
            isSos = snap.localSosActive,
            latitude = snap.localLatitude,
            longitude = snap.localLongitude,
            altitudeMeters = snap.localAltitudeMeters,
            accuracyMeters = snap.localGpsAccuracyMeters
        )
    }

    private fun updateWifiDirectDetail(detail: String) {
        MeshRepository.updateSnapshot(forceImmediate = true) {
            copy(wifiDirectDetail = detail)
        }
    }

    private fun updateConnectedTcpCount() {
        val active = clientHandlers.values.count { !it.isClosed() }
        MeshRepository.updateSnapshot(forceImmediate = true) {
            copy(connectedTcpSockets = active)
        }
    }

    private fun inspectLocalInterfaces(): Pair<List<String>, Boolean> {
        val ips = mutableListOf<String>()
        var hotspotOn = false
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList<String>() to false
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        ips.add(ip)
                        if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap") ||
                            name.startsWith("wlan1") || name.startsWith("wlan2") ||
                            ip.startsWith("192.168.43.") || ip.startsWith("192.168.137.")
                        ) {
                            hotspotOn = true
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return ips.distinct() to hotspotOn
    }

    private fun enumerateBroadcastAddresses(): List<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            if (ifaces != null) {
                for (iface in ifaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    for (ifaceAddr in iface.interfaceAddresses) {
                        val bcast = ifaceAddr.broadcast
                        if (bcast is Inet4Address) {
                            result.add(bcast)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        try {
            result.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Throwable) {
        }
        return result.toList()
    }

    private inner class ClientConnection(
        val key: String,
        val remoteIp: String,
        private val socket: Socket
    ) {
        val createdAtMs: Long = System.currentTimeMillis()
        @Volatile
        var lastRxMs: Long = createdAtMs
        @Volatile
        var remoteNodeId: String? = null

        private val writeLock = Object()
        private val sendQueue = ArrayDeque<String>()
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

        init {
            try {
                socket.tcpNoDelay = true
            } catch (_: Throwable) {
            }
            executor.execute { writeLoop() }
        }

        fun isClosed(): Boolean = socket.isClosed || !socket.isConnected

        fun sendLine(jsonLine: String, isAudio: Boolean = false) {
            synchronized(writeLock) {
                if (isClosed()) return
                if (isAudio && sendQueue.size >= 12) return
                sendQueue.addLast(jsonLine)
                writeLock.notifyAll()
            }
        }

        private fun writeLoop() {
            try {
                while (running && !isClosed()) {
                    var line: String?
                    synchronized(writeLock) {
                        while (running && !isClosed() && sendQueue.isEmpty()) {
                            writeLock.wait(1000L)
                        }
                        line = if (sendQueue.isNotEmpty()) sendQueue.removeFirst() else null
                    }
                    val toWrite = line ?: continue
                    writer.write(toWrite)
                    writer.newLine()
                    writer.flush()
                }
            } catch (_: Throwable) {
            } finally {
                closeQuietly()
            }
        }

        fun startReading() {
            executor.execute {
                try {
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    while (running && !socket.isClosed) {
                        val line = reader.readLine() ?: break
                        lastRxMs = System.currentTimeMillis()
                        if (line.length <= 65_536) {
                            onControlJsonReceived(line, remoteIp, "TCP:$key")
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    closeQuietly()
                }
            }
        }

        fun closeQuietly(updateUi: Boolean = true) {
            clientHandlers.remove(key)
            synchronized(writeLock) {
                sendQueue.clear()
                writeLock.notifyAll()
            }
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            if (updateUi) {
                updateConnectedTcpCount()
            }
        }
    }
}
