package com.sanepo.offgrid.model

enum class TransportLinkType(val shortLabel: String) {
    BLE_BEACON("BLE"),
    BLE_GATT("BT"),
    WIFI_DIRECT("P2P"),
    LOCAL_LAN("LAN")
}

enum class NodeHealthState {
    ONLINE,
    SOS,
    STALE
}

data class PeerNode(
    val nodeId: String,
    val callsign: String,
    val channelNumber: Int,
    val linkTypes: Set<TransportLinkType> = emptySet(),
    val ipAddress: String? = null,
    val wifiDirectDeviceAddress: String? = null,
    val wifiDirectDeviceName: String? = null,
    val bleRssiDbm: Int? = null,
    val estimatedBleMeters: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val gpsAccuracyMeters: Float? = null,
    val batteryPercent: Int = -1,
    val isSos: Boolean = false,
    val isTransmittingAudio: Boolean = false,
    val lastSeenMs: Long = System.currentTimeMillis()
) {
    fun healthState(nowMs: Long = System.currentTimeMillis()): NodeHealthState {
        return when {
            isSos -> NodeHealthState.SOS
            nowMs - lastSeenMs > 45_000L -> NodeHealthState.STALE
            else -> NodeHealthState.ONLINE
        }
    }

    fun hasGpsFix(): Boolean = latitude != null && longitude != null
}

data class WifiDirectDiscoveredPeer(
    val deviceAddress: String,
    val deviceName: String,
    val callsign: String?,
    val channelNumber: Int?,
    val statusCode: Int,
    val isGroupOwner: Boolean
)

data class ChatMessage(
    val msgId: String,
    val senderId: String,
    val senderCallsign: String,
    val channelNumber: Int,
    val text: String,
    val timestampMs: Long,
    val isOutgoing: Boolean,
    val isSos: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val ackedByNodes: Set<String> = emptySet()
)

data class MeshStatusSnapshot(
    val isEngineRunning: Boolean = false,
    val localNodeId: String = "00000000",
    val localCallsign: String = "UNIT-01",
    val channelNumber: Int = 1,
    val bleSupported: Boolean = false,
    val bleEnabled: Boolean = false,
    val bleAdvertising: Boolean = false,
    val bleScanning: Boolean = false,
    val bleStatusDetail: String = "Menunggu inisialisasi BLE",
    val wifiDirectSupported: Boolean = false,
    val wifiEnabled: Boolean = false,
    val wifiDirectRole: String = "Siaga",
    val wifiDirectDetail: String = "Siap pindai perangkat sekitar",
    val wifiDirectPeersCount: Int = 0,
    val localLanIps: List<String> = emptyList(),
    val connectedTcpSockets: Int = 0,
    val bleGattConnectedCount: Int = 0,
    val activeTalkerId: String? = null,
    val activeTalkerCallsign: String? = null,
    val isLocalTransmitting: Boolean = false,
    val isPttLatched: Boolean = false,
    val micLevelNormalized: Float = 0f,
    val rxLevelNormalized: Float = 0f,
    val localSosActive: Boolean = false,
    val remoteSosNodes: List<PeerNode> = emptyList(),
    val gpsEnabled: Boolean = false,
    val localLatitude: Double? = null,
    val localLongitude: Double? = null,
    val localAltitudeMeters: Double? = null,
    val localGpsAccuracyMeters: Float? = null,
    val gpsStatusText: String = "Mencari satelit GNSS...",
    val deviceAzimuthDeg: Float = 0f,
    val compassSupported: Boolean = true,
    val batteryPercent: Int = 100,
    val lastError: String? = null
)
