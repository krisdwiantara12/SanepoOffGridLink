package com.sanepo.offgrid.engine

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sanepo.offgrid.model.TransportLinkType
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BleGattTransport(
    private val context: Context,
    private val onControlJsonReceived: (json: String, senderNodeIdHint: String?, sourcePeerKey: String) -> Unit,
    private val onAudioFrameReceived: (frame: OffGridProtocol.AudioFramePacket, sourcePeerKey: String) -> Unit,
    private val onPeerLinkReady: (peerNodeId: String?, sourcePeerKey: String) -> Unit = { _, _ -> },
    private val onPeerLinkLost: (peerNodeId: String?) -> Unit = {}
) {
    companion object {
        val UUID_SERVICE: UUID = UUID.fromString("00005A4E-0000-1000-8000-00805F9B34FB")
        val UUID_CHAR_DATA: UUID = UUID.fromString("00005A4F-0000-1000-8000-00805F9B34FB")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private enum class ClientStage {
        CONNECTING,
        MTU_WAIT,
        DISCOVERING,
        CCCD_WRITING,
        READY
    }

    private data class QueuedFrame(
        val chunks: ArrayDeque<ByteArray>,
        val noResponse: Boolean,
        val isAudio: Boolean
    )

    private data class OutboundClientSession(
        val nodeId: String,
        val deviceAddress: String,
        var gatt: BluetoothGatt? = null,
        var dataChar: BluetoothGattCharacteristic? = null,
        var mtuPayload: Int = 20,
        var stage: ClientStage = ClientStage.CONNECTING,
        var cccdRetries: Int = 0,
        var ready: Boolean = false,
        var inFlight: Boolean = false,
        var chunkRetries: Int = 0,
        var writeToken: Long = 0L,
        val controlFrames: ArrayDeque<QueuedFrame> = ArrayDeque(),
        val audioFrames: ArrayDeque<QueuedFrame> = ArrayDeque(),
        var activeFrame: QueuedFrame? = null,
        val accumulator: GattFrameAccumulator = GattFrameAccumulator()
    )

    private data class InboundServerPeer(
        val device: BluetoothDevice,
        var nodeId: String? = null,
        var mtuPayload: Int = 20,
        var subscribed: Boolean = false,
        var inFlight: Boolean = false,
        var chunkRetries: Int = 0,
        var writeToken: Long = 0L,
        val controlFrames: ArrayDeque<QueuedFrame> = ArrayDeque(),
        val audioFrames: ArrayDeque<QueuedFrame> = ArrayDeque(),
        var activeFrame: QueuedFrame? = null,
        val accumulator: GattFrameAccumulator = GattFrameAccumulator()
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val btAdapter: BluetoothAdapter? = btManager?.adapter

    private var running = false
    private var gattServer: BluetoothGattServer? = null
    private var serverChar: BluetoothGattCharacteristic? = null

    private val clientSessions = ConcurrentHashMap<String, OutboundClientSession>()
    private val serverPeers = ConcurrentHashMap<String, InboundServerPeer>()
    private val macToNodeId = ConcurrentHashMap<String, String>()
    private val lastAttemptByNode = ConcurrentHashMap<String, Long>()
    private val lock = Any()

    fun start() {
        if (running) return
        val adapter = btAdapter ?: return
        if (!adapter.isEnabled) return
        if (!PermissionHelper.hasBleConnectPermission(context)) return
        running = true
        openGattServer()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        mainHandler.removeCallbacksAndMessages(null)
        val hasPerm = PermissionHelper.hasBleConnectPermission(context)
        synchronized(lock) {
            if (hasPerm) {
                for (session in clientSessions.values) {
                    try {
                        session.gatt?.disconnect()
                        session.gatt?.close()
                    } catch (_: Throwable) {
                    }
                }
            }
            clientSessions.clear()
            serverPeers.clear()
            if (hasPerm) {
                try {
                    gattServer?.clearServices()
                    gattServer?.close()
                } catch (_: Throwable) {
                }
            }
            gattServer = null
            serverChar = null
        }
        MeshRepository.clearTransportTypeAcrossPeers(TransportLinkType.BLE_GATT)
        updateGattCount()
    }

    fun onBluetoothEnabled() {
        if (!running) start()
    }

    fun onBluetoothDisabled() {
        stop()
    }

    fun onBeaconSeen(device: BluetoothDevice, packet: OffGridProtocol.BleBeaconPacket) {
        if (!running) {
            if (btAdapter?.isEnabled == true && PermissionHelper.hasBleConnectPermission(context)) {
                start()
            } else {
                return
            }
        }
        if (!PermissionHelper.hasBleConnectPermission(context)) return
        val localId = MeshRepository.getSnapshot().localNodeId
        if (packet.nodeId == localId) return

        val mac = device.address ?: return
        macToNodeId[mac] = packet.nodeId
        serverPeers[mac]?.nodeId = packet.nodeId

        val serverLinkedForNode = serverPeers.values.any { it.subscribed && it.nodeId == packet.nodeId }
        if (serverPeers[mac]?.subscribed == true || serverLinkedForNode) return
        val existing = clientSessions[packet.nodeId]
        if (existing != null && existing.gatt != null && existing.ready) return

        val now = System.currentTimeMillis()
        val lastTry = lastAttemptByNode[packet.nodeId] ?: 0L
        val minWait = if (localId > packet.nodeId) 5_500L else 8_000L
        if (now - lastTry < minWait) return

        // Stagger initial connection slightly so higher node ID initiates cleanly first
        if (lastTry == 0L && localId < packet.nodeId) {
            lastAttemptByNode[packet.nodeId] = now - (minWait - 3_000L)
            return
        }
        lastAttemptByNode[packet.nodeId] = now
        connectClient(device, packet.nodeId)
    }

    fun hasActiveConnections(): Boolean {
        return clientSessions.values.any { it.ready } || serverPeers.values.any { it.subscribed }
    }

    fun getConnectedNodeIds(): Set<String> {
        val ids = HashSet<String>()
        for (s in clientSessions.values) {
            if (s.ready) ids.add(s.nodeId)
        }
        for (p in serverPeers.values) {
            if (p.subscribed) {
                val id = p.nodeId ?: macToNodeId[p.device.address]
                if (id != null) ids.add(id)
            }
        }
        return ids
    }

    fun sendControlJsonToAll(
        json: String,
        highPriority: Boolean = true,
        excludePeerKey: String? = null
    ) {
        if (!running || btAdapter?.isEnabled != true) return
        if (!PermissionHelper.hasBleConnectPermission(context)) return
        val framed = GattFrameAccumulator.wrapFrame(OffGridProtocol.GATT_TYPE_JSON, json.toByteArray(Charsets.UTF_8))
        enqueueFrameToAllPeers(framed, urgentNoResponse = !highPriority, isAudio = false, excludePeerKey = excludePeerKey)
    }

    fun sendAudioFrameToAll(audioPacket: ByteArray, excludePeerKey: String? = null) {
        if (!running || btAdapter?.isEnabled != true) return
        if (!PermissionHelper.hasBleConnectPermission(context)) return
        val framed = GattFrameAccumulator.wrapFrame(OffGridProtocol.GATT_TYPE_AUDIO, audioPacket)
        enqueueFrameToAllPeers(framed, urgentNoResponse = true, isAudio = true, excludePeerKey = excludePeerKey)
    }

    private fun enqueueFrameToAllPeers(
        framedBytes: ByteArray,
        urgentNoResponse: Boolean,
        isAudio: Boolean,
        excludePeerKey: String?
    ) {
        val sentNodeIds = HashSet<String>()
        synchronized(lock) {
            for (session in clientSessions.values) {
                if (!session.ready || session.gatt == null || session.dataChar == null) continue
                val key = clientPeerKey(session.nodeId, session.deviceAddress)
                if (excludePeerKey == key) continue
                if (!sentNodeIds.add(session.nodeId)) continue
                enqueueIntoQueues(
                    framedBytes = framedBytes,
                    noResponse = urgentNoResponse,
                    isAudio = isAudio,
                    mtuPayload = session.mtuPayload,
                    controlFrames = session.controlFrames,
                    audioFrames = session.audioFrames
                )
                drainClientQueue(session)
            }
            for (peer in serverPeers.values) {
                if (!peer.subscribed) continue
                val peerNodeId = peer.nodeId ?: macToNodeId[peer.device.address]
                val key = serverPeerKey(peer.device.address)
                if (excludePeerKey == key) continue
                if (peerNodeId != null && !sentNodeIds.add(peerNodeId)) continue
                enqueueIntoQueues(
                    framedBytes = framedBytes,
                    noResponse = true,
                    isAudio = isAudio,
                    mtuPayload = peer.mtuPayload,
                    controlFrames = peer.controlFrames,
                    audioFrames = peer.audioFrames
                )
                drainServerQueue(peer)
            }
        }
    }

    private fun enqueueIntoQueues(
        framedBytes: ByteArray,
        noResponse: Boolean,
        isAudio: Boolean,
        mtuPayload: Int,
        controlFrames: ArrayDeque<QueuedFrame>,
        audioFrames: ArrayDeque<QueuedFrame>
    ) {
        val step = maxOf(20, mtuPayload)
        val chunks = ArrayDeque<ByteArray>()
        var pos = 0
        while (pos < framedBytes.size) {
            val end = minOf(framedBytes.size, pos + step)
            chunks.addLast(framedBytes.copyOfRange(pos, end))
            pos = end
        }
        val qFrame = QueuedFrame(chunks = chunks, noResponse = noResponse, isAudio = isAudio)
        if (isAudio) {
            // Keep max 10 buffered whole audio frames so latency stays real-time without fragmenting active frames
            while (audioFrames.size >= 10) {
                audioFrames.removeFirst()
            }
            audioFrames.addLast(qFrame)
        } else {
            // Cap extreme backlog at 48 control frames, never dropping Chat/Control silently under normal load
            while (controlFrames.size >= 48) {
                controlFrames.removeFirst()
            }
            controlFrames.addLast(qFrame)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openGattServer() {
        val mgr = btManager ?: return
        try {
            val server = mgr.openGattServer(context, serverCallback) ?: return
            val service = BluetoothGattService(UUID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val charData = BluetoothGattCharacteristic(
                UUID_CHAR_DATA,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val cccd = BluetoothGattDescriptor(
                UUID_CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            charData.addDescriptor(cccd)
            service.addCharacteristic(charData)
            server.addService(service)
            gattServer = server
            serverChar = charData
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectClient(device: BluetoothDevice, nodeId: String) {
        val mac = device.address ?: return
        val session = OutboundClientSession(nodeId = nodeId, deviceAddress = mac)
        synchronized(lock) {
            clientSessions[nodeId]?.let { old ->
                try {
                    old.gatt?.disconnect()
                    old.gatt?.close()
                } catch (_: Throwable) {
                }
            }
            clientSessions[nodeId] = session
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    session.gatt = gatt
                    session.stage = ClientStage.MTU_WAIT
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            gatt.setPreferredPhy(
                                BluetoothDevice.PHY_LE_2M_MASK,
                                BluetoothDevice.PHY_LE_2M_MASK,
                                BluetoothDevice.PHY_OPTION_NO_PREFERRED
                            )
                        }
                    } catch (_: Throwable) {
                    }
                    // Step 1: serialize MTU request slightly after link establishment
                    mainHandler.postDelayed({
                        if (!running || session.gatt !== gatt || session.stage != ClientStage.MTU_WAIT) return@postDelayed
                        val mtuStarted = try {
                            gatt.requestMtu(517)
                        } catch (_: Throwable) {
                            false
                        }
                        if (!mtuStarted) {
                            beginServiceDiscovery(session, gatt)
                        } else {
                            // Safety fallback if OEM stack drops onMtuChanged
                            mainHandler.postDelayed({
                                if (running && session.gatt === gatt && session.stage == ClientStage.MTU_WAIT) {
                                    beginServiceDiscovery(session, gatt)
                                }
                            }, 1350L)
                        }
                    }, 180L)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val lostId = session.nodeId
                    synchronized(lock) {
                        session.ready = false
                        session.inFlight = false
                        session.activeFrame = null
                        session.controlFrames.clear()
                        session.audioFrames.clear()
                        clientSessions.remove(nodeId)
                        try {
                            gatt.close()
                        } catch (_: Throwable) {
                        }
                    }
                    if (!hasAnyConnectionForNode(lostId)) {
                        MeshRepository.clearPeerLinkType(lostId, TransportLinkType.BLE_GATT)
                        onPeerLinkLost(lostId)
                    }
                    updateGattCount()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                session.mtuPayload = maxOf(20, mtu - 3)
                if (session.stage == ClientStage.MTU_WAIT) {
                    beginServiceDiscovery(session, gatt)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS || session.stage != ClientStage.DISCOVERING) return
                val svc = gatt.getService(UUID_SERVICE) ?: return
                val ch = svc.getCharacteristic(UUID_CHAR_DATA) ?: return
                session.dataChar = ch
                try {
                    gatt.setCharacteristicNotification(ch, true)
                } catch (_: Throwable) {
                }
                val cccd = ch.getDescriptor(UUID_CCCD)
                if (cccd != null) {
                    session.stage = ClientStage.CCCD_WRITING
                    session.cccdRetries = 0
                    attemptWriteCccd(session, gatt, cccd)
                } else {
                    markClientReadyAndGreet(session)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == UUID_CCCD && session.stage == ClientStage.CCCD_WRITING) {
                    markClientReadyAndGreet(session)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                synchronized(lock) {
                    session.inFlight = false
                    session.chunkRetries = 0
                    session.writeToken++
                    drainClientQueue(session)
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    val bytes = characteristic.value ?: return
                    val key = clientPeerKey(session.nodeId, session.deviceAddress)
                    handleIncomingBytes(session.accumulator, bytes, session.nodeId, session.deviceAddress, key)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                val key = clientPeerKey(session.nodeId, session.deviceAddress)
                handleIncomingBytes(session.accumulator, value, session.nodeId, session.deviceAddress, key)
            }
        }

        try {
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, callback)
            }
            session.gatt = gatt
        } catch (_: Throwable) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun beginServiceDiscovery(session: OutboundClientSession, gatt: BluetoothGatt) {
        if (!running || session.gatt !== gatt) return
        session.stage = ClientStage.DISCOVERING
        try {
            gatt.discoverServices()
        } catch (_: Throwable) {
        }
    }

    private fun attemptWriteCccd(
        session: OutboundClientSession,
        gatt: BluetoothGatt,
        cccd: BluetoothGattDescriptor
    ) {
        if (!running || session.gatt !== gatt || session.stage != ClientStage.CCCD_WRITING) return
        val ok = writeDescriptorCompat(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (ok) {
            // Safety fallback if OEM fails to emit onDescriptorWrite despite returning true
            mainHandler.postDelayed({
                if (running && session.gatt === gatt && session.stage == ClientStage.CCCD_WRITING && !session.ready) {
                    markClientReadyAndGreet(session)
                }
            }, 1400L)
        } else if (session.cccdRetries < 5) {
            session.cccdRetries++
            mainHandler.postDelayed({
                attemptWriteCccd(session, gatt, cccd)
            }, 130L)
        } else {
            markClientReadyAndGreet(session)
        }
    }

    private fun markClientReadyAndGreet(session: OutboundClientSession) {
        if (session.ready) return
        session.stage = ClientStage.READY
        session.ready = true
        updateGattCount()
        val hello = buildHelloEnvelope()
        sendControlJsonToAll(OffGridProtocol.encodeControlJson(hello), highPriority = true)
        onPeerLinkReady(session.nodeId, clientPeerKey(session.nodeId, session.deviceAddress))
    }

    @SuppressLint("MissingPermission")
    private fun drainClientQueue(session: OutboundClientSession) {
        if (session.inFlight) return
        val gatt = session.gatt ?: return
        val ch = session.dataChar ?: return

        val frame = selectNextFrame(session.activeFrame, session.controlFrames, session.audioFrames)
            ?: return
        session.activeFrame = frame
        val chunk = frame.chunks.peekFirst()
        if (chunk == null) {
            session.activeFrame = null
            drainClientQueue(session)
            return
        }

        val writeType = if (frame.noResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val ok = writeCharacteristicCompat(gatt, ch, chunk, writeType)
        if (ok) {
            frame.chunks.removeFirst()
            if (frame.chunks.isEmpty()) {
                session.activeFrame = null
            }
            session.inFlight = true
            session.chunkRetries = 0
            val token = ++session.writeToken
            mainHandler.postDelayed({
                synchronized(lock) {
                    if (session.inFlight && session.writeToken == token) {
                        session.inFlight = false
                        session.writeToken++
                        drainClientQueue(session)
                    }
                }
            }, 1000L)
        } else {
            // Radio stack temporarily busy: NEVER discard chunk on control/chat frames; retry cleanly
            val maxRetries = if (frame.isAudio) 3 else 10
            if (session.chunkRetries < maxRetries) {
                session.chunkRetries++
                mainHandler.postDelayed({
                    synchronized(lock) {
                        drainClientQueue(session)
                    }
                }, 45L)
            } else {
                // Only after repeated hardware failure on whole audio frame, drop remaining active audio frame
                if (frame.isAudio) {
                    session.activeFrame = null
                } else {
                    frame.chunks.removeFirst()
                    if (frame.chunks.isEmpty()) session.activeFrame = null
                }
                session.chunkRetries = 0
                drainClientQueue(session)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainServerQueue(peer: InboundServerPeer) {
        if (peer.inFlight) return
        val server = gattServer ?: return
        val ch = serverChar ?: return

        val frame = selectNextFrame(peer.activeFrame, peer.controlFrames, peer.audioFrames)
            ?: return
        peer.activeFrame = frame
        val chunk = frame.chunks.peekFirst()
        if (chunk == null) {
            peer.activeFrame = null
            drainServerQueue(peer)
            return
        }

        val ok = notifyCharacteristicCompat(server, peer.device, ch, chunk)
        if (ok) {
            frame.chunks.removeFirst()
            if (frame.chunks.isEmpty()) {
                peer.activeFrame = null
            }
            peer.inFlight = true
            peer.chunkRetries = 0
            val token = ++peer.writeToken
            mainHandler.postDelayed({
                synchronized(lock) {
                    if (peer.inFlight && peer.writeToken == token) {
                        peer.inFlight = false
                        peer.writeToken++
                        drainServerQueue(peer)
                    }
                }
            }, 1000L)
        } else {
            val maxRetries = if (frame.isAudio) 3 else 10
            if (peer.chunkRetries < maxRetries) {
                peer.chunkRetries++
                mainHandler.postDelayed({
                    synchronized(lock) {
                        drainServerQueue(peer)
                    }
                }, 45L)
            } else {
                if (frame.isAudio) {
                    peer.activeFrame = null
                } else {
                    frame.chunks.removeFirst()
                    if (frame.chunks.isEmpty()) peer.activeFrame = null
                }
                peer.chunkRetries = 0
                drainServerQueue(peer)
            }
        }
    }

    private fun selectNextFrame(
        active: QueuedFrame?,
        controlFrames: ArrayDeque<QueuedFrame>,
        audioFrames: ArrayDeque<QueuedFrame>
    ): QueuedFrame? {
        if (active != null && active.chunks.isNotEmpty()) return active
        return if (controlFrames.isNotEmpty()) {
            controlFrames.removeFirst()
        } else if (audioFrames.isNotEmpty()) {
            audioFrames.removeFirst()
        } else {
            null
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val mac = device.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                synchronized(lock) {
                    if (!serverPeers.containsKey(mac)) {
                        serverPeers[mac] = InboundServerPeer(device, nodeId = macToNodeId[mac])
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val removed: InboundServerPeer?
                synchronized(lock) {
                    removed = serverPeers.remove(mac)
                }
                val lostId = removed?.nodeId ?: macToNodeId[mac]
                if (lostId != null && !hasAnyConnectionForNode(lostId)) {
                    MeshRepository.clearPeerLinkType(lostId, TransportLinkType.BLE_GATT)
                    onPeerLinkLost(lostId)
                }
                updateGattCount()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val mac = device.address ?: return
            serverPeers[mac]?.mtuPayload = maxOf(20, mtu - 3)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val mac = device.address ?: return
            val peer = serverPeers[mac] ?: InboundServerPeer(device, nodeId = macToNodeId[mac]).also {
                serverPeers[mac] = it
            }
            var newlySubscribed = false
            if (descriptor.uuid == UUID_CCCD) {
                val enabled = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                newlySubscribed = enabled && !peer.subscribed
                peer.subscribed = enabled
                updateGattCount()
                if (enabled) {
                    mainHandler.postDelayed({
                        val hello = buildHelloEnvelope()
                        sendControlJsonToAll(OffGridProtocol.encodeControlJson(hello), highPriority = true)
                        if (newlySubscribed) {
                            onPeerLinkReady(peer.nodeId ?: macToNodeId[mac], serverPeerKey(mac))
                        }
                    }, 160L)
                }
            }
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                } catch (_: Throwable) {
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (_: Throwable) {
                }
            }
            val mac = device.address ?: return
            val bytes = value ?: return
            val peer = serverPeers[mac] ?: InboundServerPeer(device, nodeId = macToNodeId[mac]).also {
                serverPeers[mac] = it
            }
            handleIncomingBytes(peer.accumulator, bytes, peer.nodeId, mac, serverPeerKey(mac))
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val mac = device.address ?: return
            val peer = serverPeers[mac] ?: return
            synchronized(lock) {
                peer.inFlight = false
                peer.chunkRetries = 0
                peer.writeToken++
                drainServerQueue(peer)
            }
        }
    }

    private fun handleIncomingBytes(
        accumulator: GattFrameAccumulator,
        chunk: ByteArray,
        nodeIdHint: String?,
        sourceMac: String?,
        sourcePeerKey: String
    ) {
        val frames = synchronized(accumulator) { accumulator.feed(chunk) }
        for ((type, payload) in frames) {
            when (type) {
                OffGridProtocol.GATT_TYPE_JSON -> {
                    val json = String(payload, Charsets.UTF_8)
                    val decoded = OffGridProtocol.decodeControlJson(json)
                    if (decoded is OffGridProtocol.ControlEnvelope.Hello && decoded.hopCount == 0 && sourceMac != null) {
                        macToNodeId[sourceMac] = decoded.nodeId
                        serverPeers[sourceMac]?.nodeId = decoded.nodeId
                    }
                    onControlJsonReceived(json, nodeIdHint, sourcePeerKey)
                }
                OffGridProtocol.GATT_TYPE_AUDIO -> {
                    val audio = OffGridProtocol.decodeAudioFrame(payload, payload.size)
                    if (audio != null) {
                        if (sourceMac != null && !macToNodeId.containsKey(sourceMac)) {
                            macToNodeId[sourceMac] = audio.senderNodeId
                            serverPeers[sourceMac]?.nodeId = audio.senderNodeId
                        }
                        onAudioFrameReceived(audio, sourcePeerKey)
                    }
                }
            }
        }
    }

    private fun hasAnyConnectionForNode(nodeId: String): Boolean {
        val clientOk = clientSessions[nodeId]?.ready == true
        val serverOk = serverPeers.values.any { it.subscribed && (it.nodeId ?: macToNodeId[it.device.address]) == nodeId }
        return clientOk || serverOk
    }

    private fun buildHelloEnvelope(): OffGridProtocol.ControlEnvelope.Hello {
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

    private fun clientPeerKey(nodeId: String, mac: String): String = "BLE_C:$nodeId:$mac"

    private fun serverPeerKey(mac: String): String = "BLE_S:$mac"

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        chunk: ByteArray,
        writeType: Int
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, chunk, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.writeType = writeType
                ch.value = chunk
                gatt.writeCharacteristic(ch)
            }
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        desc: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, value) == BluetoothStatusCodes.SUCCESS
            } else {
                desc.value = value
                gatt.writeDescriptor(desc)
            }
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun notifyCharacteristicCompat(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        ch: BluetoothGattCharacteristic,
        chunk: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, ch, false, chunk) == BluetoothStatusCodes.SUCCESS
            } else {
                ch.value = chunk
                server.notifyCharacteristicChanged(device, ch, false)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun updateGattCount() {
        val active = clientSessions.values.count { it.ready } + serverPeers.values.count { it.subscribed }
        MeshRepository.updateSnapshot(forceImmediate = true) {
            copy(bleGattConnectedCount = active)
        }
    }
}
