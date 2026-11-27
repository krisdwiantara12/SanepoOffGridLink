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
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BleGattTransport(
    private val context: Context,
    private val onControlJsonReceived: (json: String, senderNodeIdHint: String?) -> Unit,
    private val onAudioFrameReceived: (OffGridProtocol.AudioFramePacket) -> Unit
) {
    companion object {
        val UUID_SERVICE: UUID = UUID.fromString("00005A4E-0000-1000-8000-00805F9B34FB")
        val UUID_CHAR_DATA: UUID = UUID.fromString("00005A4F-0000-1000-8000-00805F9B34FB")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val btAdapter: BluetoothAdapter? = btManager?.adapter

    private var running = false
    private var gattServer: BluetoothGattServer? = null
    private var serverChar: BluetoothGattCharacteristic? = null

    private data class OutboundClientSession(
        val nodeId: String,
        val deviceAddress: String,
        var gatt: BluetoothGatt? = null,
        var dataChar: BluetoothGattCharacteristic? = null,
        var mtuPayload: Int = 20,
        var ready: Boolean = false,
        var servicesRequested: Boolean = false,
        var inFlight: Boolean = false,
        var writeToken: Long = 0L,
        val queue: ArrayDeque<Pair<ByteArray, Boolean>> = ArrayDeque(),
        val accumulator: GattFrameAccumulator = GattFrameAccumulator()
    )

    private data class InboundServerPeer(
        val device: BluetoothDevice,
        var nodeId: String? = null,
        var mtuPayload: Int = 20,
        var subscribed: Boolean = false,
        var inFlight: Boolean = false,
        var writeToken: Long = 0L,
        val queue: ArrayDeque<ByteArray> = ArrayDeque(),
        val accumulator: GattFrameAccumulator = GattFrameAccumulator()
    )

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
        val minWait = if (localId > packet.nodeId) 5_000L else 7_000L
        if (now - lastTry < minWait) return

        // Wait 3.5s on initial lower-ID sighting so higher ID connects once cleanly, then fall back
        if (lastTry == 0L && localId < packet.nodeId) {
            lastAttemptByNode[packet.nodeId] = now - (minWait - 3_500L)
            return
        }
        lastAttemptByNode[packet.nodeId] = now
        connectClient(device, packet.nodeId)
    }

    fun hasActiveConnections(): Boolean {
        return clientSessions.values.any { it.ready } || serverPeers.values.any { it.subscribed }
    }

    fun sendControlJsonToAll(json: String, highPriority: Boolean = true) {
        if (!running || btAdapter?.isEnabled != true) return
        if (!PermissionHelper.hasBleConnectPermission(context)) return
        val framed = GattFrameAccumulator.wrapFrame(OffGridProtocol.GATT_TYPE_JSON, json.toByteArray(Charsets.UTF_8))
        enqueueFrameToAllPeers(framed, urgentNoResponse = !highPriority, isAudio = false)
    }

    fun sendAudioFrameToAll(audioPacket: ByteArray) {
        if (!running || btAdapter?.isEnabled != true) return
        if (!PermissionHelper.hasBleConnectPermission(context)) return
        val framed = GattFrameAccumulator.wrapFrame(OffGridProtocol.GATT_TYPE_AUDIO, audioPacket)
        enqueueFrameToAllPeers(framed, urgentNoResponse = true, isAudio = true)
    }

    private fun enqueueFrameToAllPeers(
        framedBytes: ByteArray,
        urgentNoResponse: Boolean,
        isAudio: Boolean
    ) {
        val sentNodeIds = HashSet<String>()
        synchronized(lock) {
            for (session in clientSessions.values) {
                if (!session.ready || session.gatt == null || session.dataChar == null) continue
                if (!sentNodeIds.add(session.nodeId)) continue
                val step = maxOf(20, session.mtuPayload)
                val neededChunks = (framedBytes.size + step - 1) / step
                // Check whole-frame queue headroom before splitting so partial frames never corrupt GATT sync
                if (isAudio && session.queue.size + neededChunks > 24) continue
                var pos = 0
                while (pos < framedBytes.size) {
                    val end = minOf(framedBytes.size, pos + step)
                    session.queue.addLast(framedBytes.copyOfRange(pos, end) to urgentNoResponse)
                    pos = end
                }
                drainClientQueue(session)
            }
            for (peer in serverPeers.values) {
                if (!peer.subscribed) continue
                val peerNodeId = peer.nodeId ?: macToNodeId[peer.device.address]
                if (peerNodeId != null && !sentNodeIds.add(peerNodeId)) continue
                val step = maxOf(20, peer.mtuPayload)
                val neededChunks = (framedBytes.size + step - 1) / step
                if (isAudio && peer.queue.size + neededChunks > 24) continue
                var pos = 0
                while (pos < framedBytes.size) {
                    val end = minOf(framedBytes.size, pos + step)
                    peer.queue.addLast(framedBytes.copyOfRange(pos, end))
                    pos = end
                }
                drainServerQueue(peer)
            }
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
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        val mtuStarted = gatt.requestMtu(517)
                        if (!mtuStarted) {
                            session.servicesRequested = true
                            gatt.discoverServices()
                        } else {
                            // Some MediaTek/OEM BLE stacks drop onMtuChanged; ensure service discovery always starts
                            mainHandler.postDelayed({
                                if (!session.servicesRequested && running && session.gatt === gatt) {
                                    session.servicesRequested = true
                                    try {
                                        gatt.discoverServices()
                                    } catch (_: Throwable) {
                                    }
                                }
                            }, 1500L)
                        }
                    } catch (_: Throwable) {
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    synchronized(lock) {
                        session.ready = false
                        session.inFlight = false
                        session.queue.clear()
                        clientSessions.remove(nodeId)
                        try {
                            gatt.close()
                        } catch (_: Throwable) {
                        }
                    }
                    updateGattCount()
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                session.mtuPayload = maxOf(20, mtu - 3)
                if (!session.servicesRequested) {
                    session.servicesRequested = true
                    try {
                        gatt.discoverServices()
                    } catch (_: Throwable) {
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                val svc = gatt.getService(UUID_SERVICE) ?: return
                val ch = svc.getCharacteristic(UUID_CHAR_DATA) ?: return
                session.dataChar = ch
                gatt.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(UUID_CCCD)
                if (cccd != null) {
                    writeDescriptorCompat(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    markClientReadyAndGreet(session)
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid == UUID_CCCD) {
                    markClientReadyAndGreet(session)
                }
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                synchronized(lock) {
                    session.inFlight = false
                    session.writeToken++
                    drainClientQueue(session)
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    val bytes = characteristic.value ?: return
                    handleIncomingBytes(session.accumulator, bytes, session.nodeId, session.deviceAddress)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                handleIncomingBytes(session.accumulator, value, session.nodeId, session.deviceAddress)
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

    private fun markClientReadyAndGreet(session: OutboundClientSession) {
        session.ready = true
        updateGattCount()
        val snap = MeshRepository.getSnapshot()
        val hello = OffGridProtocol.ControlEnvelope.Hello(
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
        sendControlJsonToAll(OffGridProtocol.encodeControlJson(hello), highPriority = true)
    }

    @SuppressLint("MissingPermission")
    private fun drainClientQueue(session: OutboundClientSession) {
        if (session.inFlight || session.queue.isEmpty()) return
        val gatt = session.gatt ?: return
        val ch = session.dataChar ?: return
        val (chunk, noResponse) = session.queue.removeFirst()
        session.inFlight = true
        val token = ++session.writeToken
        val writeType = if (noResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val ok = writeCharacteristicCompat(gatt, ch, chunk, writeType)
        if (!ok) {
            session.inFlight = false
            session.writeToken++
        } else {
            mainHandler.postDelayed({
                synchronized(lock) {
                    if (session.inFlight && session.writeToken == token) {
                        session.inFlight = false
                        session.writeToken++
                        drainClientQueue(session)
                    }
                }
            }, 1200L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun drainServerQueue(peer: InboundServerPeer) {
        if (peer.inFlight || peer.queue.isEmpty()) return
        val server = gattServer ?: return
        val ch = serverChar ?: return
        val chunk = peer.queue.removeFirst()
        peer.inFlight = true
        val token = ++peer.writeToken
        val ok = notifyCharacteristicCompat(server, peer.device, ch, chunk)
        if (!ok) {
            peer.inFlight = false
            peer.writeToken++
        } else {
            mainHandler.postDelayed({
                synchronized(lock) {
                    if (peer.inFlight && peer.writeToken == token) {
                        peer.inFlight = false
                        peer.writeToken++
                        drainServerQueue(peer)
                    }
                }
            }, 1200L)
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
                synchronized(lock) {
                    serverPeers.remove(mac)
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
            if (descriptor.uuid == UUID_CCCD) {
                val enabled = value != null && value.isNotEmpty() && (value[0].toInt() and 0x01) != 0
                peer.subscribed = enabled
                updateGattCount()
                if (enabled) {
                    mainHandler.postDelayed({
                        val snap = MeshRepository.getSnapshot()
                        val hello = OffGridProtocol.ControlEnvelope.Hello(
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
                        sendControlJsonToAll(OffGridProtocol.encodeControlJson(hello), highPriority = true)
                    }, 180L)
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
            handleIncomingBytes(peer.accumulator, bytes, peer.nodeId, mac)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val mac = device.address ?: return
            val peer = serverPeers[mac] ?: return
            synchronized(lock) {
                peer.inFlight = false
                peer.writeToken++
                drainServerQueue(peer)
            }
        }
    }

    private fun handleIncomingBytes(
        accumulator: GattFrameAccumulator,
        chunk: ByteArray,
        nodeIdHint: String?,
        sourceMac: String?
    ) {
        val frames = synchronized(accumulator) { accumulator.feed(chunk) }
        for ((type, payload) in frames) {
            when (type) {
                OffGridProtocol.GATT_TYPE_JSON -> {
                    val json = String(payload, Charsets.UTF_8)
                    val decoded = OffGridProtocol.decodeControlJson(json)
                    if (decoded is OffGridProtocol.ControlEnvelope.Hello && sourceMac != null) {
                        macToNodeId[sourceMac] = decoded.nodeId
                        serverPeers[sourceMac]?.nodeId = decoded.nodeId
                    }
                    onControlJsonReceived(json, nodeIdHint)
                }
                OffGridProtocol.GATT_TYPE_AUDIO -> {
                    val audio = OffGridProtocol.decodeAudioFrame(payload, payload.size)
                    if (audio != null) {
                        if (sourceMac != null) {
                            macToNodeId[sourceMac] = audio.senderNodeId
                            serverPeers[sourceMac]?.nodeId = audio.senderNodeId
                        }
                        onAudioFrameReceived(audio)
                    }
                }
            }
        }
    }

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
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, value)
            } else {
                desc.value = value
                gatt.writeDescriptor(desc)
            }
        } catch (_: Throwable) {
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
