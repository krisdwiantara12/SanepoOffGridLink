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
import com.sanepo.offgrid.model.PeerNode
import com.sanepo.offgrid.model.WifiDirectDiscoveredPeer
import java.util.Date

class ChatMessageAdapter : RecyclerView.Adapter<ChatMessageAdapter.Holder>() {
    private val items = mutableListOf<ChatMessage>()

    fun submitList(newItems: List<ChatMessage>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_chat_message, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val root: LinearLayout = view.findViewById(R.id.chatRowRoot)
        private val bubble: LinearLayout = view.findViewById(R.id.chatBubbleContainer)
        private val tvMeta: TextView = view.findViewById(R.id.tvChatMeta)
        private val tvBody: TextView = view.findViewById(R.id.tvChatBody)
        private val btnMapCoord: TextView = view.findViewById(R.id.tvChatCoords)

        fun bind(msg: ChatMessage) {
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
            val ackSuffix = if (msg.isOutgoing && msg.ackedByNodes.isNotEmpty()) {
                " | Diterima ${msg.ackedByNodes.size} unit"
            } else ""
            tvMeta.text = "${msg.senderCallsign} · CH-%02d · $timeStr$ackSuffix".format(msg.channelNumber)
            tvBody.text = msg.text

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
    private val onSelectPeer: (PeerNode) -> Unit
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
        holder.bind(items[position], localLat, localLon, onSelectPeer)
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvCallsign: TextView = view.findViewById(R.id.tvPeerCallsign)
        private val tvBadge: TextView = view.findViewById(R.id.tvPeerLinkBadge)
        private val tvTelemetry: TextView = view.findViewById(R.id.tvPeerTelemetry)

        fun bind(
            peer: PeerNode,
            localLat: Double?,
            localLon: Double?,
            onClick: (PeerNode) -> Unit
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

            val linkTags = peer.linkTypes.joinToString("+") { it.shortLabel }.ifBlank { "SCAN" }
            tvBadge.text = linkTags

            val distText = if (localLat != null && localLon != null && peer.latitude != null && peer.longitude != null) {
                val meters = GeoMath.haversineDistanceMeters(localLat, localLon, peer.latitude, peer.longitude)
                val bearing = GeoMath.initialBearingDegrees(localLat, localLon, peer.latitude, peer.longitude)
                val card = GeoMath.cardinalDirectionId(bearing)
                "GPS ${GeoMath.formatDistanceCompact(meters)} · ${bearing.toInt()}° $card"
            } else if (peer.bleRssiDbm != null && peer.estimatedBleMeters != null) {
                "BLE ${peer.bleRssiDbm} dBm (~${GeoMath.formatDistanceCompact(peer.estimatedBleMeters)})"
            } else {
                "Terhubung tanpa koordinat GPS"
            }

            val batText = if (peer.batteryPercent in 0..100) " · Bat ${peer.batteryPercent}%" else ""
            val ipText = if (!peer.ipAddress.isNullOrBlank()) " · IP ${peer.ipAddress}" else ""
            tvTelemetry.text = "$distText$batText$ipText"

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
            val ownerTag = if (peer.isGroupOwner) "Host Grup" else "Perangkat"
            tvSub.text = "${peer.deviceAddress} · $ownerTag"
            btnConnect.setOnClickListener { onClick(peer) }
        }
    }
}
