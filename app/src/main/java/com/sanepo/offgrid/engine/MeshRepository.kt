package com.sanepo.offgrid.engine

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.sanepo.offgrid.model.ChatMessage
import com.sanepo.offgrid.model.MeshStatusSnapshot
import com.sanepo.offgrid.model.PeerNode
import com.sanepo.offgrid.model.TransportLinkType
import com.sanepo.offgrid.model.WifiDirectDiscoveredPeer
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

object MeshRepository {

    interface Observer {
        fun onMeshUpdated(
            snapshot: MeshStatusSnapshot,
            peers: List<PeerNode>,
            wifiDirectPeers: List<WifiDirectDiscoveredPeer>,
            messages: List<ChatMessage>
        )
    }

    private const val PREFS_NAME = "sanepo_offgrid_prefs"
    private const val KEY_NODE_ID = "local_node_id"
    private const val KEY_CALLSIGN = "local_callsign"
    private const val KEY_CHANNEL = "local_channel"
    private const val MAX_MESSAGES = 200

    private val mainHandler = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArraySet<Observer>()
    private val peersWriteLock = Any()
    private val peersMap = ConcurrentHashMap<String, PeerNode>()
    private val p2pDiscoveredMap = ConcurrentHashMap<String, WifiDirectDiscoveredPeer>()
    private val messagesList = CopyOnWriteArrayList<ChatMessage>()
    private val seenMessageIds: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var snapshot = MeshStatusSnapshot()

    @Volatile
    private var initialized = false

    @Volatile
    private var lastNotifyMs = 0L

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            var nodeId = prefs.getString(KEY_NODE_ID, null)
            if (nodeId.isNullOrBlank()) {
                val rnd = SecureRandom().nextInt()
                nodeId = OffGridProtocol.formatNodeIdHex(rnd)
                prefs.edit().putString(KEY_NODE_ID, nodeId).apply()
            }
            val defaultCallsign = OffGridProtocol.sanitizeCallsign(
                Build.MODEL.ifBlank { "UNIT-${nodeId.take(4)}" },
                maxLen = 12
            )
            val callsign = OffGridProtocol.sanitizeCallsign(
                prefs.getString(KEY_CALLSIGN, defaultCallsign) ?: defaultCallsign,
                maxLen = 12
            )
            val channel = OffGridProtocol.clampChannel(prefs.getInt(KEY_CHANNEL, 1))
            snapshot = snapshot.copy(
                localNodeId = nodeId,
                localCallsign = callsign,
                channelNumber = channel
            )
            initialized = true
        }
    }

    fun addObserver(observer: Observer) {
        observers.add(observer)
        val snap = snapshot
        val peers = getPeersSorted()
        val p2p = getWifiDirectPeersSorted()
        val msgs = messagesList.toList()
        mainHandler.post {
            observer.onMeshUpdated(snap, peers, p2p, msgs)
        }
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    fun getSnapshot(): MeshStatusSnapshot = snapshot

    fun getPeersSorted(): List<PeerNode> {
        val now = System.currentTimeMillis()
        return peersMap.values
            .filter { node -> node.isSos || (now - node.lastSeenMs) < 300_000L }
            .sortedWith(
                compareByDescending<PeerNode> { it.isSos }
                    .thenByDescending { it.channelNumber == snapshot.channelNumber }
                    .thenByDescending { it.lastSeenMs }
            )
    }

    fun getWifiDirectPeersSorted(): List<WifiDirectDiscoveredPeer> {
        return p2pDiscoveredMap.values.sortedBy { it.callsign ?: it.deviceName }
    }

    fun getMessages(): List<ChatMessage> = messagesList.toList()

    fun updateIdentity(context: Context, callsign: String, channelNumber: Int) {
        val cleanCallsign = OffGridProtocol.sanitizeCallsign(callsign, maxLen = 12)
        val cleanChannel = OffGridProtocol.clampChannel(channelNumber)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CALLSIGN, cleanCallsign)
            .putInt(KEY_CHANNEL, cleanChannel)
            .apply()
        updateSnapshot {
            copy(localCallsign = cleanCallsign, channelNumber = cleanChannel)
        }
    }

    fun updateSnapshot(forceImmediate: Boolean = true, transform: MeshStatusSnapshot.() -> MeshStatusSnapshot) {
        synchronized(this) {
            val updated = snapshot.transform()
            val remoteSos = peersMap.values.filter { it.isSos }
            snapshot = updated.copy(remoteSosNodes = remoteSos)
        }
        notifyObservers(forceImmediate)
    }

    private inline fun mutatePeer(nodeId: String, updater: (PeerNode?) -> PeerNode) {
        synchronized(peersWriteLock) {
            peersMap[nodeId] = updater(peersMap[nodeId])
        }
    }

    fun upsertPeerFromBle(packet: OffGridProtocol.BleBeaconPacket, rssiDbm: Int) {
        if (packet.nodeId == snapshot.localNodeId) return
        val now = System.currentTimeMillis()
        val estDist = GeoMath.estimateDistanceFromRssi(rssiDbm)
        mutatePeer(packet.nodeId) { existing ->
            val currentCallsign = if (existing != null && existing.callsign.length > packet.callsign.length &&
                existing.linkTypes.any { it != TransportLinkType.BLE_BEACON }
            ) {
                existing.callsign
            } else {
                packet.callsign
            }
            val lat = packet.latitude ?: existing?.latitude
            val lon = packet.longitude ?: existing?.longitude
            (existing ?: PeerNode(nodeId = packet.nodeId, callsign = currentCallsign, channelNumber = packet.channelNumber)).copy(
                callsign = currentCallsign,
                channelNumber = packet.channelNumber,
                linkTypes = (existing?.linkTypes ?: emptySet()) + TransportLinkType.BLE_BEACON,
                bleRssiDbm = rssiDbm,
                estimatedBleMeters = estDist,
                latitude = lat,
                longitude = lon,
                batteryPercent = if (packet.batteryPercent in 0..100) packet.batteryPercent else (existing?.batteryPercent ?: -1),
                isSos = packet.isSos,
                lastSeenMs = now
            )
        }
        syncRemoteSosIntoSnapshot()
        notifyObservers(forceImmediate = false)
    }

    fun upsertPeerFromNetwork(
        hello: OffGridProtocol.ControlEnvelope.Hello,
        remoteIp: String?,
        viaWifiDirect: Boolean,
        viaBleGatt: Boolean = false
    ) {
        if (hello.nodeId == snapshot.localNodeId) return
        val now = System.currentTimeMillis()
        val linkType = when {
            viaBleGatt -> TransportLinkType.BLE_GATT
            viaWifiDirect -> TransportLinkType.WIFI_DIRECT
            else -> TransportLinkType.LOCAL_LAN
        }
        mutatePeer(hello.nodeId) { existing ->
            (existing ?: PeerNode(
                nodeId = hello.nodeId,
                callsign = hello.callsign,
                channelNumber = hello.channelNumber
            )).copy(
                callsign = hello.callsign,
                channelNumber = hello.channelNumber,
                linkTypes = (existing?.linkTypes ?: emptySet()) + linkType,
                ipAddress = remoteIp ?: existing?.ipAddress,
                latitude = hello.latitude ?: existing?.latitude,
                longitude = hello.longitude ?: existing?.longitude,
                altitudeMeters = hello.altitudeMeters ?: existing?.altitudeMeters,
                gpsAccuracyMeters = hello.accuracyMeters ?: existing?.gpsAccuracyMeters,
                batteryPercent = if (hello.batteryPercent in 0..100) hello.batteryPercent else (existing?.batteryPercent ?: -1),
                isSos = hello.isSos,
                lastSeenMs = now
            )
        }
        syncRemoteSosIntoSnapshot()
        notifyObservers(forceImmediate = false)
    }

    fun updatePeerSosState(sos: OffGridProtocol.ControlEnvelope.SosSignal, remoteIp: String?) {
        if (sos.senderId == snapshot.localNodeId) return
        val now = System.currentTimeMillis()
        mutatePeer(sos.senderId) { existing ->
            (existing ?: PeerNode(
                nodeId = sos.senderId,
                callsign = sos.senderCallsign,
                channelNumber = snapshot.channelNumber
            )).copy(
                callsign = sos.senderCallsign,
                ipAddress = remoteIp ?: existing?.ipAddress,
                latitude = sos.latitude ?: existing?.latitude,
                longitude = sos.longitude ?: existing?.longitude,
                batteryPercent = if (sos.batteryPercent in 0..100) sos.batteryPercent else (existing?.batteryPercent ?: -1),
                isSos = sos.active,
                lastSeenMs = now
            )
        }
        syncRemoteSosIntoSnapshot()
        notifyObservers(forceImmediate = true)
    }

    fun updatePeerAudioFloor(floor: OffGridProtocol.ControlEnvelope.PttFloor) {
        if (floor.senderId == snapshot.localNodeId) return
        synchronized(peersWriteLock) {
            val existing = peersMap[floor.senderId]
            if (existing != null) {
                peersMap[floor.senderId] = existing.copy(
                    isTransmittingAudio = floor.speaking,
                    lastSeenMs = System.currentTimeMillis()
                )
            }
        }
        updateSnapshot(forceImmediate = true) {
            if (floor.speaking) {
                copy(activeTalkerId = floor.senderId, activeTalkerCallsign = floor.senderCallsign)
            } else if (activeTalkerId == floor.senderId) {
                copy(activeTalkerId = null, activeTalkerCallsign = null, rxLevelNormalized = 0f)
            } else {
                this
            }
        }
    }

    fun replaceWifiDirectDiscoveredPeers(peers: List<WifiDirectDiscoveredPeer>) {
        p2pDiscoveredMap.clear()
        for (peer in peers) {
            p2pDiscoveredMap[peer.deviceAddress] = peer
        }
        updateSnapshot(forceImmediate = true) {
            copy(wifiDirectPeersCount = peers.size)
        }
    }

    fun appendIncomingMessage(chat: OffGridProtocol.ControlEnvelope.Chat): Boolean {
        if (!seenMessageIds.add(chat.msgId)) return false
        val msg = ChatMessage(
            msgId = chat.msgId,
            senderId = chat.senderId,
            senderCallsign = chat.senderCallsign,
            channelNumber = chat.channelNumber,
            text = chat.text,
            timestampMs = chat.timestampMs,
            isOutgoing = chat.senderId == snapshot.localNodeId,
            isSos = chat.isSos,
            latitude = chat.latitude,
            longitude = chat.longitude
        )
        addBoundedMessage(msg)
        notifyObservers(forceImmediate = true)
        return true
    }

    fun appendOutgoingMessage(msg: ChatMessage) {
        seenMessageIds.add(msg.msgId)
        addBoundedMessage(msg)
        notifyObservers(forceImmediate = true)
    }

    fun markMessageAcked(msgId: String, receiverId: String) {
        var changed = false
        for (i in messagesList.indices) {
            val item = messagesList[i]
            if (item.msgId == msgId && !item.ackedByNodes.contains(receiverId)) {
                messagesList[i] = item.copy(ackedByNodes = item.ackedByNodes + receiverId)
                changed = true
                break
            }
        }
        if (changed) notifyObservers(forceImmediate = true)
    }

    private fun addBoundedMessage(msg: ChatMessage) {
        messagesList.add(msg)
        while (messagesList.size > MAX_MESSAGES) {
            messagesList.removeAt(0)
        }
    }

    private fun syncRemoteSosIntoSnapshot() {
        synchronized(this) {
            val remoteSos = peersMap.values.filter { it.isSos }
            snapshot = snapshot.copy(remoteSosNodes = remoteSos)
        }
    }

    private fun notifyObservers(forceImmediate: Boolean) {
        val now = System.currentTimeMillis()
        if (!forceImmediate && (now - lastNotifyMs) < 160L) {
            return
        }
        lastNotifyMs = now
        val snap = snapshot
        val peers = getPeersSorted()
        val p2p = getWifiDirectPeersSorted()
        val msgs = messagesList.toList()
        mainHandler.post {
            for (observer in observers) {
                observer.onMeshUpdated(snap, peers, p2p, msgs)
            }
        }
    }
}
