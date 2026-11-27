package com.sanepo.offgrid.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.sanepo.offgrid.R
import com.sanepo.offgrid.engine.GeoMath
import com.sanepo.offgrid.model.ChatMessage
import com.sanepo.offgrid.model.NodeHealthState
import com.sanepo.offgrid.model.PeerNode
import com.sanepo.offgrid.model.TransportLinkType
import com.sanepo.offgrid.model.WifiDirectDiscoveredPeer
import java.util.Date

class ChatMessageAdapter(
    private val onRetryClicked: (ChatMessage) -> Unit = {}
) : RecyclerView.Adapter<ChatMessageAdapter.Holder>() {
    private val items = mutableListOf<ChatMessage>()
    private var peerNames: Map<String, String> = emptyMap()

    fun submitList(newItems: List<ChatMessage>, knownPeers: List<PeerNode> = emptyList()) {
        items.clear()
        items.addAll(newItems)
        if (knownPeers.isNotEmpty()) {
            peerNames = knownPeers.associate { it.nodeId to it.callsign }
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], peerNames, onRetryClicked)
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val root: LinearLayout = view.findViewById(R.id.chatRowRoot)
        private val bubble: LinearLayout = view.findViewById(R.id.chatBubbleContainer)
        private val tvMeta: TextView = view.findViewById(R.id.tvChatMeta)
        private val tvBody: TextView = view.findViewById(R.id.tvChatBody)
        private val btnMapCoord: TextView = view.findViewById(R.id.tvChatCoords)
        private val tvDeliveryState: TextView = view.findViewById(R.id.tvChatDeliveryStatus)

        fun bind(
            msg: ChatMessage,
            peerNames: Map<String, String>,
            onRetryClicked: (ChatMessage) -> Unit
        ) {
            val ctx = itemView.context
            root.gravity = if (msg.isOutgoing) Gravity.END else Gravity.START

            when {
                msg.isSos -> {
                    bubble.setBackgroundResource(R.drawable.bg_sos_btn)
                    tvMeta.setTextColor(ContextCompat.getColor(ctx, R.color.ink_on_sos))
                    tvBody.setTextColor(ContextCompat.getColor(ctx, R.color.ink_on_sos))
                }
                msg.isOutgoing -> {
                    bubble.setBackgroundResource(R.drawable.bg_keycap_btn)
                    tvMeta.setTextColor(ContextCompat.getColor(ctx, R.color.accent_ink))
                    tvBody.setTextColor(ContextCompat.getColor(ctx, R.color.ink_primary))
                }
                else -> {
                    bubble.setBackgroundResource(R.drawable.bg_console_card)
                    tvMeta.setTextColor(ContextCompat.getColor(ctx, R.color.status_live))
                    tvBody.setTextColor(ContextCompat.getColor(ctx, R.color.ink_primary))
                }
            }

            val timeStr = DateFormat.format("HH:mm", Date(msg.timestampMs)).toString()
            tvMeta.text = "${msg.senderCallsign} · CH-%02d · $timeStr".format(msg.channelNumber)
            tvBody.text = msg.text

            if (msg.isOutgoing) {
                tvDeliveryState.visibility = View.VISIBLE
                when {
                    msg.ackedByNodes.isNotEmpty() -> {
                        val firstId = msg.ackedByNodes.first()
                        val firstName = peerNames[firstId] ?: firstId.take(6)
                        tvDeliveryState.text = if (msg.ackedByNodes.size == 1) {
                            "Diterima oleh $firstName"
                        } else {
                            "Diterima oleh ${msg.ackedByNodes.size} teman"
                        }
                        tvDeliveryState.setTextColor(
                            ContextCompat.getColor(ctx, if (msg.isSos) R.color.ink_on_sos else R.color.status_live)
                        )
                        tvDeliveryState.setOnClickListener(null)
                    }
                    msg.waitingForSignal -> {
                        tvDeliveryState.text = "Menunggu jangkauan · Ketuk kirim ulang"
                        tvDeliveryState.setTextColor(
                            ContextCompat.getColor(ctx, if (msg.isSos) R.color.ink_on_sos else R.color.status_warn)
                        )
                        tvDeliveryState.setOnClickListener { onRetryClicked(msg) }
                    }
                    else -> {
                        tvDeliveryState.text = "Mengirim & menunggu bukti terima..."
                        tvDeliveryState.setTextColor(
                            ContextCompat.getColor(ctx, if (msg.isSos) R.color.ink_on_sos else R.color.ink_secondary)
                        )
                        tvDeliveryState.setOnClickListener { onRetryClicked(msg) }
                    }
                }
            } else {
                tvDeliveryState.visibility = View.GONE
                tvDeliveryState.setOnClickListener(null)
            }

            if (msg.latitude != null && msg.longitude != null) {
                btnMapCoord.visibility = View.VISIBLE
                btnMapCoord.text = "Koordinat: %.5f, %.5f (Ketuk buka peta)".format(msg.latitude, msg.longitude)
                btnMapCoord.setOnClickListener {
                    val uri = Uri.parse("geo:${msg.latitude},${msg.longitude}?q=${msg.latitude},${msg.longitude}(${Uri.encode(msg.senderCallsign)})")
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Throwable) {
                    }
                }
            } else {
                btnMapCoord.visibility = View.GONE
            }
        }
    }
}

class PeerNodeAdapter(
    private val onSelectPeer: (PeerNode) -> Unit,
    private val onPingPeer: ((PeerNode) -> Unit)? = null
) : RecyclerView.Adapter<PeerNodeAdapter.Holder>() {

    private val items = mutableListOf<PeerNode>()
    private var localLat: Double? = null
    private var localLon: Double? = null

    fun submitList(newItems: List<PeerNode>, lat: Double?, lon: Double?) {
        items.clear()
        items.addAll(newItems)
        localLat = lat
        localLon = lon
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_peer_node, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], localLat, localLon, onSelectPeer, onPingPeer)
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvCallsign: TextView = view.findViewById(R.id.tvPeerCallsign)
        private val tvBadge: TextView = view.findViewById(R.id.tvPeerLinkBadge)
        private val tvTelemetry: TextView = view.findViewById(R.id.tvPeerTelemetry)
        private val btnPing: Button = view.findViewById(R.id.btnPeerPing)

        fun bind(
            peer: PeerNode,
            localLat: Double?,
            localLon: Double?,
            onClick: (PeerNode) -> Unit,
            onPing: ((PeerNode) -> Unit)?
        ) {
            val ctx = itemView.context
            tvCallsign.text = if (peer.isSos) {
                "SOS | ${peer.callsign} (CH-%02d)".format(peer.channelNumber)
            } else {
                "${peer.callsign} · CH-%02d".format(peer.channelNumber)
            }
            tvCallsign.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    when {
                        peer.isSos -> R.color.sos_ink
                        peer.isTransmittingAudio -> R.color.accent_ink
                        else -> R.color.ink_primary
                    }
                )
            )

            val readyStateText = when {
                peer.linkTypes.contains(TransportLinkType.WIFI_DIRECT) -> "Wi-Fi Direct (Siap)"
                peer.linkTypes.contains(TransportLinkType.LOCAL_LAN) -> "Hotspot/Wi-Fi (Siap)"
                peer.linkTypes.contains(TransportLinkType.BLE_GATT) -> "Bluetooth (Siap)"
                peer.linkTypes.contains(TransportLinkType.MESH_RELAY) ->
                    "Estafet ${peer.hopCount}x (via ${peer.relayViaCallsign ?: "Mesh"})"
                else -> "Terdeteksi Sinyal"
            }
            tvBadge.text = readyStateText
            tvBadge.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (peer.hasTwoWayLink()) R.color.status_live else R.color.status_warn
                )
            )

            val now = System.currentTimeMillis()
            val ageSec = ((now - peer.lastSeenMs) / 1000L).coerceAtLeast(0L)
            val ageText = when {
                ageSec < 5L -> "Aktif"
                ageSec < 60L -> "${ageSec} dtk lalu"
                else -> "${ageSec / 60L} mnt lalu"
            }

            val distText = if (localLat != null && localLon != null && peer.latitude != null && peer.longitude != null) {
                val meters = GeoMath.haversineDistanceMeters(localLat, localLon, peer.latitude, peer.longitude)
                val bearing = GeoMath.initialBearingDegrees(localLat, localLon, peer.latitude, peer.longitude)
                val card = GeoMath.cardinalDirectionId(bearing)
                "GPS ${GeoMath.formatDistanceCompact(meters)} (${bearing.toInt()}° $card)"
            } else if (peer.estimatedBleMeters != null) {
                "Sinyal ~${GeoMath.formatDistanceCompact(peer.estimatedBleMeters)}"
            } else if (peer.hasTwoWayLink()) {
                "Menunggu GPS teman"
            } else {
                "Sedang menyambung..."
            }

            val pingPart = peer.lastPingRttMs?.let { rtt ->
                val hopNote = if (peer.lastPingHops > 0) " ${peer.lastPingHops + 1} hop" else ""
                " · Ping ${rtt}ms$hopNote"
            } ?: ""
            val batText = if (peer.batteryPercent in 0..100) " · Bat ${peer.batteryPercent}%" else ""
            val staleTag = if (peer.healthState(now) == NodeHealthState.STALE) " · Sinyal Redup" else ""
            tvTelemetry.text = "$distText · $ageText$batText$pingPart$staleTag"

            if (onPing != null && peer.hasTwoWayLink()) {
                btnPing.visibility = View.VISIBLE
                btnPing.setOnClickListener { onPing(peer) }
            } else {
                btnPing.visibility = View.GONE
                btnPing.setOnClickListener(null)
            }

            itemView.setOnClickListener { onClick(peer) }
        }
    }
}

class WifiDirectPeerAdapter(
    private val onConnectClicked: (WifiDirectDiscoveredPeer) -> Unit
) : RecyclerView.Adapter<WifiDirectPeerAdapter.Holder>() {

    private val items = mutableListOf<WifiDirectDiscoveredPeer>()

    fun submitList(newItems: List<WifiDirectDiscoveredPeer>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_p2p_discovered, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], onConnectClicked)
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvName: TextView = view.findViewById(R.id.tvP2pDeviceName)
        private val tvSub: TextView = view.findViewById(R.id.tvP2pDeviceAddress)
        private val btnConnect: Button = view.findViewById(R.id.btnP2pConnect)

        fun bind(peer: WifiDirectDiscoveredPeer, onClick: (WifiDirectDiscoveredPeer) -> Unit) {
            val label = if (!peer.callsign.isNullOrBlank()) {
                "${peer.callsign} (CH-%02d)".format(peer.channelNumber ?: 1)
            } else {
                peer.deviceName
            }
            tvName.text = label
            tvSub.text = if (peer.isGroupOwner) {
                "Sudah membuat rombongan · Ketuk Gabung"
            } else {
                "Perangkat Sanepo di sekitar · Siap gabung"
            }
            btnConnect.setOnClickListener { onClick(peer) }
        }
    }
}
