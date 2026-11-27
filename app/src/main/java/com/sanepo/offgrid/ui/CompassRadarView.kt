package com.sanepo.offgrid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.sanepo.offgrid.R
import com.sanepo.offgrid.engine.GeoMath
import com.sanepo.offgrid.model.PeerNode
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class CompassRadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class PlottedBlip(
        val peer: PeerNode,
        val screenX: Float,
        val screenY: Float,
        val hasExactBearing: Boolean,
        val distanceMeters: Double,
        val bearingDeg: Double?
    )

    var onPeerSelected: ((PeerNode) -> Unit)? = null

    private var localLat: Double? = null
    private var localLon: Double? = null
    private var deviceAzimuthDeg: Float = 0f
    private var peers: List<PeerNode> = emptyList()
    private var selectedNodeId: String? = null
    private val lastBlips = mutableListOf<PlottedBlip>()

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }
    private val dashedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val bgDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val headingConePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val blipRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val sosTrianglePath = Path()

    fun updateRadarData(
        localLatitude: Double?,
        localLongitude: Double?,
        azimuthDeg: Float,
        peerList: List<PeerNode>,
        selectedId: String?
    ) {
        localLat = localLatitude
        localLon = localLongitude
        deviceAzimuthDeg = azimuthDeg
        peers = peerList
        selectedNodeId = selectedId
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            var nearest: PlottedBlip? = null
            var bestDist = 72f
            for (blip in lastBlips) {
                val d = hypot((event.x - blip.screenX).toDouble(), (event.y - blip.screenY).toDouble()).toFloat()
                if (d < bestDist) {
                    bestDist = d
                    nearest = blip
                }
            }
            if (nearest != null) {
                onPeerSelected?.invoke(nearest.peer)
                performClick()
                return true
            }
        } else if (event.action == MotionEvent.ACTION_DOWN) {
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h / 2f
        val maxRadius = min(w, h) * 0.43f

        val cBg = ContextCompat.getColor(context, R.color.bg_console)
        val cStroke = ContextCompat.getColor(context, R.color.stroke_instrument)
        val cSubtle = ContextCompat.getColor(context, R.color.stroke_subtle)
        val cInkPrimary = ContextCompat.getColor(context, R.color.ink_primary)
        val cInkSecondary = ContextCompat.getColor(context, R.color.ink_secondary)
        val cAccent = ContextCompat.getColor(context, R.color.accent_ink)
        val cLive = ContextCompat.getColor(context, R.color.status_live)
        val cSos = ContextCompat.getColor(context, R.color.sos_ink)

        bgDiscPaint.color = cBg
        ringPaint.color = cStroke
        dashedRingPaint.color = cSubtle
        headingConePaint.color = cAccent
        cardinalPaint.color = cInkPrimary
        cardinalPaint.textSize = 13f * density
        labelPaint.textSize = 11f * density

        // Base circular bezel
        canvas.drawCircle(cx, cy, maxRadius, bgDiscPaint)
        canvas.drawCircle(cx, cy, maxRadius, ringPaint)
        canvas.drawCircle(cx, cy, maxRadius * 0.68f, dashedRingPaint)
        canvas.drawCircle(cx, cy, maxRadius * 0.34f, dashedRingPaint)

        // Heading reference tick fixed at 12 o'clock (phone top = forward look)
        canvas.drawLine(cx, cy - maxRadius - 8f * density, cx, cy - maxRadius + 8f * density, headingConePaint)
        canvas.drawLine(cx - 8f * density, cy, cx + 8f * density, cy, ringPaint)
        canvas.drawLine(cx, cy - 8f * density, cx, cy + 8f * density, ringPaint)

        // Rotated cardinal labels N/E/S/W
        val cardinals = arrayOf("U" to 0.0, "T" to 90.0, "S" to 180.0, "B" to 270.0)
        for ((letter, worldAngle) in cardinals) {
            val relRad = Math.toRadians(worldAngle - deviceAzimuthDeg)
            val tx = cx + (maxRadius - 14f * density) * sin(relRad).toFloat()
            val ty = cy - (maxRadius - 14f * density) * cos(relRad).toFloat() + 4f * density
            cardinalPaint.color = if (letter == "U") cAccent else cInkSecondary
            canvas.drawText(letter, tx, ty, cardinalPaint)
        }

        // Compute dynamic scale radius from peers with GPS
        val lat = localLat
        val lon = localLon
        var maxGpsMeters = 300.0
        if (lat != null && lon != null) {
            for (p in peers) {
                if (p.latitude != null && p.longitude != null) {
                    val d = GeoMath.haversineDistanceMeters(lat, lon, p.latitude, p.longitude)
                    maxGpsMeters = max(maxGpsMeters, d * 1.18)
                }
            }
        }
        val scaleMeters = maxGpsMeters

        lastBlips.clear()
        var bleFallbackIdx = 0
        val usableRadius = maxRadius * 0.84f

        for (peer in peers) {
            val peerLat = peer.latitude
            val peerLon = peer.longitude
            val hasGpsPair = lat != null && lon != null && peerLat != null && peerLon != null

            val blipX: Float
            val blipY: Float
            val distM: Double
            val bearingDeg: Double?

            if (hasGpsPair) {
                distM = GeoMath.haversineDistanceMeters(lat!!, lon!!, peerLat!!, peerLon!!)
                bearingDeg = GeoMath.initialBearingDegrees(lat, lon, peerLat, peerLon)
                val relRad = Math.toRadians(bearingDeg - deviceAzimuthDeg)
                val normR = (distM / scaleMeters).toFloat().coerceIn(0.12f, 0.96f)
                val rPx = usableRadius * normR
                blipX = cx + rPx * sin(relRad).toFloat()
                blipY = cy - rPx * cos(relRad).toFloat()
            } else {
                distM = peer.estimatedBleMeters ?: 25.0
                bearingDeg = null
                // BLE-only peers placed cleanly on outer RSSI perimeter ring with deterministic angular spacing
                val slotAngleRad = Math.toRadians((bleFallbackIdx * 52.0) + 25.0)
                bleFallbackIdx++
                val rPx = usableRadius * (0.35f + ((distM.toFloat() / 80f).coerceIn(0.05f, 0.55f)))
                blipX = cx + rPx * sin(slotAngleRad).toFloat()
                blipY = cy - rPx * cos(slotAngleRad).toFloat()
            }

            lastBlips.add(
                PlottedBlip(
                    peer = peer,
                    screenX = blipX,
                    screenY = blipY,
                    hasExactBearing = hasGpsPair,
                    distanceMeters = distM,
                    bearingDeg = bearingDeg
                )
            )

            val isSelected = peer.nodeId == selectedNodeId
            val markerColor = when {
                peer.isSos -> cSos
                peer.isTransmittingAudio -> cAccent
                hasGpsPair -> cLive
                else -> cAccent
            }

            if (isSelected) {
                blipRingPaint.color = cInkPrimary
                canvas.drawCircle(blipX, blipY, 13f * density, blipRingPaint)
            }

            blipPaint.color = markerColor
            if (peer.isSos) {
                val r = 9f * density
                sosTrianglePath.reset()
                sosTrianglePath.moveTo(blipX, blipY - r)
                sosTrianglePath.lineTo(blipX + r, blipY + r)
                sosTrianglePath.lineTo(blipX - r, blipY + r)
                sosTrianglePath.close()
                canvas.drawPath(sosTrianglePath, blipPaint)
            } else if (hasGpsPair) {
                canvas.drawCircle(blipX, blipY, 7f * density, blipPaint)
            } else {
                // Hollow ring marker for RSSI-only (honest non-GPS distance indicator)
                blipRingPaint.color = markerColor
                canvas.drawCircle(blipX, blipY, 7f * density, blipRingPaint)
            }

            val distTag = if (hasGpsPair) {
                GeoMath.formatDistanceCompact(distM)
            } else {
                "~${GeoMath.formatDistanceCompact(distM)}"
            }
            labelPaint.color = if (peer.isSos) cSos else cInkPrimary
            val labelX = (blipX + 10f * density).coerceAtMost(w - 92f * density)
            val labelY = (blipY + 4f * density).coerceIn(18f * density, h - 10f * density)
            canvas.drawText("${peer.callsign.take(8)} $distTag", labelX, labelY, labelPaint)
        }

        // Center "YOU" dot
        blipPaint.color = cAccent
        canvas.drawCircle(cx, cy, 5f * density, blipPaint)

        // Bottom right scale note
        labelPaint.color = cInkSecondary
        val scaleText = "Skala Radar: ${GeoMath.formatDistanceCompact(scaleMeters)}"
        canvas.drawText(scaleText, 12f * density, h - 8f * density, labelPaint)
    }
}
