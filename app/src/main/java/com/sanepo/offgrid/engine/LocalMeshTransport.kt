package com.sanepo.offgrid.engine

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
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
    private val onAudioFrameReceived: (OffGridProtocol.AudioFramePacket) -> Unit,
    private val onRemoteChatReceived: (OffGridProtocol.ControlEnvelope.Chat) -> Unit,
    private val onRemoteSosReceived: (OffGridProtocol.ControlEnvelope.SosSignal) -> Unit
) {
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val audioTxExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
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
    private val peerUdpTargets = ConcurrentHashMap<String, String>() // nodeId -> ipAddress
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
                            wifiDirectDetail = if (enabled) "Wi-Fi Direct siap" else "Wi-Fi mati: aktifkan Wi-Fi untuk mode P2P jarak jauh"
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
        MeshRepository.updateSnapshot(forceImmediate = true) {
            copy(
                wifiDirectSupported = p2pSupported,
                wifiEnabled = wifiManager?.isWifiEnabled == true,
                localLanIps = enumerateLocalIpv4Addresses()
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
        clientHandlers.values.forEach { it.closeQuietly() }
        clientHandlers.clear()
        peerUdpTargets.clear()
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
                updateWifiDirectDetail("Membuat grup Host Wi-Fi Direct...")
            }

            override fun onFailure(reason: Int) {
                updateWifiDirectDetail("Gagal buat Host P2P (kode $reason). Coba gunakan Hotspot HP.")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun discoverPeersAndServices() {
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        if (!PermissionHelper.hasWifiDirectPermission(context)) {
            updateWifiDirectDetail("Izin Wi-Fi Sekitar / Lokasi dibutuhkan untuk pindai P2P")
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
                                updateWifiDirectDetail("Memindai perangkat Sanepo via Wi-Fi Direct...")
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
        updateWifiDirectDetail("Menghubungkan ke $deviceAddress...")
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                updateWifiDirectDetail("Permintaan sambungan P2P terkirim. Konfirmasi di HP tujuan bila muncul.")
            }

            override fun onFailure(reason: Int) {
                updateWifiDirectDetail("Gagal sambung P2P (kode $reason)")
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
                    copy(wifiDirectRole = "Siaga", wifiDirectDetail = "Grup P2P diputus")
                }
            }

            override fun onFailure(reason: Int) {
                MeshRepository.updateSnapshot(forceImmediate = true) {
                    copy(wifiDirectRole = "Siaga", wifiDirectDetail = "P2P siaga")
                }
            }
        })
    }

    fun sendControlToAll(envelope: OffGridProtocol.ControlEnvelope) {
        val json = OffGridProtocol.encodeControlJson(envelope)
        executor.execute {
            for (client in clientHandlers.values) {
                client.sendLine(json)
            }
            if (envelope is OffGridProtocol.ControlEnvelope.Chat ||
                envelope is OffGridProtocol.ControlEnvelope.SosSignal ||
                envelope is OffGridProtocol.ControlEnvelope.PttFloor
            ) {
                broadcastUdpControl(json)
            }
        }
    }

    fun hasNetworkPeers(): Boolean = clientHandlers.isNotEmpty() || udpAudioVerifiedNodes.isNotEmpty()

    fun sendAudioFrameToPeers(packetBytes: ByteArray) {
        audioTxExecutor.execute {
            val socket = udpAudioSocket
            val targetAddrs = cachedPeerInetAddrs.values
            if (socket != null) {
                for (addr in targetAddrs) {
                    try {
                        socket.send(DatagramPacket(packetBytes, packetBytes.size, addr, OffGridProtocol.UDP_AUDIO_PORT))
                    } catch (_: Throwable) {
                    }
                }
                if (targetAddrs.isEmpty()) {
                    for (bcast in cachedBroadcastAddrs) {
                        try {
                            socket.send(DatagramPacket(packetBytes, packetBytes.size, bcast, OffGridProtocol.UDP_AUDIO_PORT))
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            // Hybrid TCP fallback: if any connected TCP client hasn't proved UDP path, mirror via TCP noDelay
            if (clientHandlers.isNotEmpty() && udpAudioVerifiedNodes.size < clientHandlers.size) {
                val tcpJson = OffGridProtocol.encodeControlJson(OffGridProtocol.ControlEnvelope.TcpAudio(packetBytes))
                for (client in clientHandlers.values) {
                    client.sendLine(tcpJson, isAudio = true)
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
        val myIps = enumerateLocalIpv4Addresses()
        if (myIps.contains(targetIp)) return
        if (clientHandlers.values.any { it.remoteIp == targetIp && !it.isClosed() }) return
        executor.execute {
            try {
                if (clientHandlers.values.any { it.remoteIp == targetIp && !it.isClosed() }) return@execute
                val sock = Socket()
                sock.connect(InetSocketAddress(targetIp, OffGridProtocol.TCP_MESH_PORT), 3000)
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
                    if (enumerateLocalIpv4Addresses().contains(remoteIp)) continue
                    val raw = String(pkt.data, 0, pkt.length, Charsets.UTF_8).trim()
                    handleIncomingControlJson(raw, remoteIp, sourceKey = null)
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
                    val remoteIp = pkt.address?.hostAddress
                    val frame = OffGridProtocol.decodeAudioFrame(pkt.data, pkt.length) ?: continue
                    if (frame.senderNodeId == MeshRepository.getSnapshot().localNodeId) continue
                    udpAudioVerifiedNodes.add(frame.senderNodeId)
                    if (frame.type == OffGridProtocol.AUDIO_TYPE_PING) continue

                    // Group Owner relays live voice to all connected clients on the mesh except sender
                    if (isP2pGroupOwner && remoteIp != null) {
                        val copy = pkt.data.copyOf(pkt.length)
                        for ((nodeId, targetIp) in peerUdpTargets) {
                            if (targetIp != remoteIp && nodeId != frame.senderNodeId) {
                                val cachedAddr = cachedPeerInetAddrs[nodeId] ?: continue
                                try {
                                    sock.send(DatagramPacket(copy, copy.size, cachedAddr, OffGridProtocol.UDP_AUDIO_PORT))
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }

                    onAudioFrameReceived(frame)
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
                    cachedBroadcastAddrs = enumerateBroadcastAddresses()
                    val hello = buildLocalHelloEnvelope()
                    val json = OffGridProtocol.encodeControlJson(hello)
                    broadcastUdpControl(json)
                    for (client in clientHandlers.values) {
                        client.sendLine(json)
                    }
                    // Probe UDP 47882 path silently so we know which peers allow direct UDP audio
                    val pingBytes = OffGridProtocol.encodeAudioFrame(
                        type = OffGridProtocol.AUDIO_TYPE_PING,
                        channelNumber = hello.channelNumber,
                        senderNodeId = hello.nodeId,
                        sequence = 0,
                        pcmBytes = ByteArray(0)
                    )
                    udpAudioSocket?.let { uSock ->
                        for (addr in cachedPeerInetAddrs.values) {
                            try {
                                uSock.send(DatagramPacket(pingBytes, pingBytes.size, addr, OffGridProtocol.UDP_AUDIO_PORT))
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    val wifiOn = wifiManager?.isWifiEnabled == true
                    val lans = enumerateLocalIpv4Addresses()
                    MeshRepository.updateSnapshot(forceImmediate = false) {
                        copy(
                            wifiEnabled = wifiOn,
                            localLanIps = lans
                        )
                    }
                    cycle++
                    if (wifiOn && cycle % 7 == 0 && clientHandlers.isEmpty() && PermissionHelper.hasWifiDirectPermission(context)) {
                        discoverPeersAndServices()
                    }
                    Thread.sleep(3000L)
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
            try {
                sock.send(DatagramPacket(bytes, bytes.size, bcast, OffGridProtocol.UDP_DISCOVERY_PORT))
            } catch (_: Throwable) {
            }
        }
        for (ip in peerUdpTargets.values.toSet()) {
            try {
                sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), OffGridProtocol.UDP_DISCOVERY_PORT))
            } catch (_: Throwable) {
            }
        }
    }

    private fun handleIncomingControlJson(raw: String, remoteIp: String, sourceKey: String?) {
        val env = OffGridProtocol.decodeControlJson(raw) ?: return
        val localId = MeshRepository.getSnapshot().localNodeId
        val viaP2p = remoteIp.startsWith("192.168.49.")

        when (env) {
            is OffGridProtocol.ControlEnvelope.Hello -> {
                if (env.nodeId == localId) return
                rememberPeerUdpTarget(env.nodeId, remoteIp)
                MeshRepository.upsertPeerFromNetwork(env, remoteIp, viaWifiDirect = viaP2p)
                if (sourceKey == null && localId > env.nodeId) {
                    connectTcpToIpIfNeeded(remoteIp)
                } else if (sourceKey == null && isP2pGroupOwner) {
                    connectTcpToIpIfNeeded(remoteIp)
                }
            }
            is OffGridProtocol.ControlEnvelope.Chat -> {
                if (env.senderId == localId) return
                rememberPeerUdpTarget(env.senderId, remoteIp)
                val isNew = MeshRepository.appendIncomingMessage(env)
                if (isNew) {
                    val ack = OffGridProtocol.ControlEnvelope.ChatAck(msgId = env.msgId, receiverId = localId)
                    sendControlToAll(ack)
                    onRemoteChatReceived(env)
                    relayControlJsonIfGroupOwner(raw, sourceKey)
                }
            }
            is OffGridProtocol.ControlEnvelope.ChatAck -> {
                if (env.receiverId != localId) {
                    MeshRepository.markMessageAcked(env.msgId, env.receiverId)
                    relayControlJsonIfGroupOwner(raw, sourceKey)
                }
            }
            is OffGridProtocol.ControlEnvelope.PttFloor -> {
                if (env.senderId != localId) {
                    MeshRepository.updatePeerAudioFloor(env)
                    relayControlJsonIfGroupOwner(raw, sourceKey)
                }
            }
            is OffGridProtocol.ControlEnvelope.SosSignal -> {
                if (env.senderId != localId) {
                    rememberPeerUdpTarget(env.senderId, remoteIp)
                    MeshRepository.updatePeerSosState(env, remoteIp)
                    onRemoteSosReceived(env)
                    relayControlJsonIfGroupOwner(raw, sourceKey)
                }
            }
            is OffGridProtocol.ControlEnvelope.TcpAudio -> {
                val frame = OffGridProtocol.decodeAudioFrame(env.packetBytes, env.packetBytes.size)
                if (frame != null && frame.senderNodeId != localId) {
                    onAudioFrameReceived(frame)
                    relayControlJsonIfGroupOwner(raw, sourceKey)
                }
            }
        }
    }

    private fun relayControlJsonIfGroupOwner(rawJson: String, sourceKey: String?) {
        if (!isP2pGroupOwner || sourceKey == null) return
        for ((key, client) in clientHandlers) {
            if (key != sourceKey) {
                client.sendLine(rawJson)
            }
        }
    }

    private fun handleWifiDirectConnectionInfo(info: WifiP2pInfo?) {
        if (info == null || !info.groupFormed) {
            isP2pGroupOwner = false
            currentP2pGroupOwnerIp = null
            MeshRepository.updateSnapshot(forceImmediate = true) {
                copy(wifiDirectRole = "Siaga", wifiDirectDetail = "P2P terhubung jaringan lokal / siaga")
            }
            return
        }
        isP2pGroupOwner = info.isGroupOwner
        val ownerIp = info.groupOwnerAddress?.hostAddress
        currentP2pGroupOwnerIp = ownerIp
        if (info.isGroupOwner) {
            MeshRepository.updateSnapshot(forceImmediate = true) {
                copy(
                    wifiDirectRole = "Host GO (${ownerIp ?: "192.168.49.1"})",
                    wifiDirectDetail = "Grup aktif: perangkat lain bisa gabung langsung"
                )
            }
        } else {
            MeshRepository.updateSnapshot(forceImmediate = true) {
                copy(
                    wifiDirectRole = "Klien P2P",
                    wifiDirectDetail = "Terhubung ke Host ${ownerIp ?: "-"}"
                )
            }
            if (!ownerIp.isNullOrBlank()) {
                connectTcpToIpIfNeeded(ownerIp)
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

    private fun enumerateLocalIpv4Addresses(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return ips.distinct()
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
        private val key: String,
        val remoteIp: String,
        private val socket: Socket
    ) {
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
                if (isAudio && sendQueue.size >= 10) return
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
                        if (line.length <= 32_768) {
                            handleIncomingControlJson(line, remoteIp, sourceKey = key)
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    closeQuietly()
                }
            }
        }

        fun closeQuietly() {
            clientHandlers.remove(key)
            synchronized(writeLock) {
                sendQueue.clear()
                writeLock.notifyAll()
            }
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            updateConnectedTcpCount()
        }
    }
}
