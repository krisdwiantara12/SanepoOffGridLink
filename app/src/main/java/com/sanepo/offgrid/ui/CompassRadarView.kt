package com.sanepo.offgrid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.sanepo.offgrid.R
import com.sanepo.offgrid.engine.GeoMath
import com.sanepo.offgrid.model.NodeHealthState
import com.sanepo.offgrid.model.PeerNode
import com.sanepo.offgrid.model.RadarRangeMode
import kotlin.math.abs
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
        val distanceMeters: Double,
        val bearingDeg: Double
    )

    var onPeerSelected: ((PeerNode) -> Unit)? = null

    private var localLat: Double? = null
    private var localLon: Double? = null
    private var deviceAzimuthDeg: Float = 0f
    private var peers: List<PeerNode> = emptyList()
    private var selectedNodeId: String? = null
    private var rangeMode: RadarRangeMode = RadarRangeMode.AUTO
    private val lastBlips = mutableListOf<PlottedBlip>()

    private val bgDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val coneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
    }
    private val dashedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        pathEffect = DashPathEffect(floatArrayOf(9f, 7f), 0f)
    }
    private val targetLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val degTickTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val ringLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val blipRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val centerDotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f
    }
    private val headingPillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val headingPillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val conePath = Path()
    private val sosTrianglePath = Path()
    private val northMarkerPath = Path()
    private val scratchRect = RectF()
    private val placedLabels = mutableListOf<RectF>()

    fun setRangeMode(mode: RadarRangeMode) {
        rangeMode = mode
        invalidate()
    }

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
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                var nearest: PlottedBlip? = null
                var bestDist = 78f
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
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val cx = w / 2f
        val cy = h / 2f + 10f * density
        val maxRadius = min(w * 0.44f, (h - 24f * density) * 0.44f)

        val cCanvas = ContextCompat.getColor(context, R.color.bg_canvas)
        val cBg = ContextCompat.getColor(context, R.color.bg_console)
        val cKeycap = ContextCompat.getColor(context, R.color.bg_keycap)
        val cStroke = ContextCompat.getColor(context, R.color.stroke_instrument)
        val cSubtle = ContextCompat.getColor(context, R.color.stroke_subtle)
        val cInkPrimary = ContextCompat.getColor(context, R.color.ink_primary)
        val cInkSecondary = ContextCompat.getColor(context, R.color.ink_secondary)
        val cAccent = ContextCompat.getColor(context, R.color.accent_ink)
        val cAccentFill = ContextCompat.getColor(context, R.color.accent_fill)
        val cInkOnAccent = ContextCompat.getColor(context, R.color.ink_on_accent)
        val cLive = ContextCompat.getColor(context, R.color.status_live)
        val cSos = ContextCompat.getColor(context, R.color.sos_ink)

        bgDiscPaint.color = cBg
        ringPaint.color = cStroke
        dashedRingPaint.color = cSubtle
        cardinalPaint.textSize = 13f * density
        degTickTextPaint.textSize = 9.5f * density
        degTickTextPaint.color = cInkSecondary
        ringLabelPaint.textSize = 9.5f * density
        ringLabelPaint.color = cInkSecondary
        labelPaint.textSize = 11f * density
        centerDotStroke.color = cBg

        // 1. Base radar disc + concentric distance circles (33%, 66%, 100%)
        canvas.drawCircle(cx, cy, maxRadius, bgDiscPaint)
        canvas.drawCircle(cx, cy, maxRadius, ringPaint)
        canvas.drawCircle(cx, cy, maxRadius * 0.67f, dashedRingPaint)
        canvas.drawCircle(cx, cy, maxRadius * 0.34f, dashedRingPaint)

        // Subtle N/E/S/W & 45 deg cross-axes
        tickPaint.color = cSubtle
        tickPaint.strokeWidth = 1.2f * density
        canvas.drawLine(cx - maxRadius, cy, cx + maxRadius, cy, tickPaint)
        canvas.drawLine(cx, cy - maxRadius, cx, cy + maxRadius, tickPaint)

        // 2. Forward field-of-view cone pointing to 12 o'clock (where HP faces)
        val fovHalfRad = Math.toRadians(22.0)
        val coneR = maxRadius * 0.90f
        conePath.reset()
        conePath.moveTo(cx, cy)
        conePath.lineTo(cx - coneR * sin(fovHalfRad).toFloat(), cy - coneR * cos(fovHalfRad).toFloat())
        conePath.lineTo(cx + coneR * sin(fovHalfRad).toFloat(), cy - coneR * cos(fovHalfRad).toFloat())
        conePath.close()
        coneFillPaint.color = (cAccentFill and 0x00FFFFFF) or 0x1F000000
        canvas.drawPath(conePath, coneFillPaint)

        // 3. Rotating 360 degree tick bezel & cardinal indicators
        for (deg in 0 until 360 step 5) {
            val relRad = Math.toRadians(deg.toDouble() - deviceAzimuthDeg)
            val s = sin(relRad).toFloat()
            val c = cos(relRad).toFloat()
            val isCardinal = deg % 90 == 0
            val isOctant = deg % 45 == 0
            val isFifteen = deg % 15 == 0
            val len = when {
                isCardinal -> 10f * density
                isOctant -> 8f * density
                isFifteen -> 5.5f * density
                else -> 3f * density
            }
            tickPaint.color = when {
                deg == 0 -> cAccent
                isOctant -> cInkPrimary
                else -> cStroke
            }
            tickPaint.strokeWidth = if (isOctant) 2.2f * density else 1.2f * density
            val rOuter = maxRadius
            val rInner = maxRadius - len
            canvas.drawLine(cx + rInner * s, cy - rInner * c, cx + rOuter * s, cy - rOuter * c, tickPaint)

            if (isOctant && !isCardinal) {
                val rTxt = maxRadius - 17f * density
                canvas.drawText(
                    "$deg",
                    cx + rTxt * s,
                    cy - rTxt * c + 3.5f * density,
                    degTickTextPaint
                )
            }
        }

        val cardinals = arrayOf("U" to 0.0, "T" to 90.0, "S" to 180.0, "B" to 270.0)
        for ((letter, worldAngle) in cardinals) {
            val relRad = Math.toRadians(worldAngle - deviceAzimuthDeg)
            val tx = cx + (maxRadius - 18f * density) * sin(relRad).toFloat()
            val ty = cy - (maxRadius - 18f * density) * cos(relRad).toFloat() + 4.5f * density
            cardinalPaint.color = if (letter == "U") cAccent else cInkPrimary
            canvas.drawText(letter, tx, ty, cardinalPaint)

            if (letter == "U") {
                val tipR = maxRadius + 3f * density
                val baseR = maxRadius - 4f * density
                val nx = cx + tipR * sin(relRad).toFloat()
                val ny = cy - tipR * cos(relRad).toFloat()
                val perpX = cos(relRad).toFloat() * 4.5f * density
                val perpY = sin(relRad).toFloat() * 4.5f * density
                val bx = cx + baseR * sin(relRad).toFloat()
                val by = cy - baseR * cos(relRad).toFloat()
                northMarkerPath.reset()
                northMarkerPath.moveTo(nx, ny)
                northMarkerPath.lineTo(bx + perpX, by + perpY)
                northMarkerPath.lineTo(bx - perpX, by - perpY)
                northMarkerPath.close()
                blipPaint.color = cAccentFill
                canvas.drawPath(northMarkerPath, blipPaint)
            }
        }

        // 4. Determine Radar Range Scale (Manual Fixed or Dynamic Auto)
        val lat = localLat
        val lon = localLon
        val gpsPeers = if (lat != null && lon != null) {
            peers.filter { it.latitude != null && it.longitude != null }
        } else {
            emptyList()
        }

        val scaleMeters: Double = rangeMode.fixedMeters ?: run {
            var maxDist = 200.0
            for (p in gpsPeers) {
                val d = GeoMath.haversineDistanceMeters(lat!!, lon!!, p.latitude!!, p.longitude!!)
                maxDist = max(maxDist, d * 1.2)
            }
            // Snap to clean round numbers (200m, 500m, 1000m, 2000m, 5000m)
            when {
                maxDist <= 200.0 -> 200.0
                maxDist <= 500.0 -> 500.0
                maxDist <= 1000.0 -> 1000.0
                maxDist <= 2500.0 -> 2500.0
                else -> (maxDist / 500.0).toInt() * 500.0 + 500.0
            }
        }

        // Draw crisp distance labels on 33%, 67%, 100% rings along 42 deg diagonal
        val labelAngleRad = Math.toRadians(42.0)
        val diagSin = sin(labelAngleRad).toFloat()
        val diagCos = cos(labelAngleRad).toFloat()
        canvas.drawText(
            GeoMath.formatDistanceCompact(scaleMeters * 0.34),
            cx + maxRadius * 0.34f * diagSin + 3f * density,
            cy - maxRadius * 0.34f * diagCos,
            ringLabelPaint
        )
        canvas.drawText(
            GeoMath.formatDistanceCompact(scaleMeters * 0.67),
            cx + maxRadius * 0.67f * diagSin + 3f * density,
            cy - maxRadius * 0.67f * diagCos,
            ringLabelPaint
        )

        // 5. Plot ONLY GPS-confirmed directional peers (NEVER fake angles for non-GPS peers)
        lastBlips.clear()
        placedLabels.clear()
        val usableRadius = maxRadius * 0.85f
        val nowMs = System.currentTimeMillis()

        for (peer in gpsPeers) {
            val peerLat = peer.latitude ?: continue
            val peerLon = peer.longitude ?: continue
            val distM = GeoMath.haversineDistanceMeters(lat!!, lon!!, peerLat, peerLon)
            val bearingDeg = GeoMath.initialBearingDegrees(lat, lon, peerLat, peerLon)
            val relRad = Math.toRadians(bearingDeg - deviceAzimuthDeg)

            val isBeyondScale = distM > scaleMeters
            val normR = if (isBeyondScale) 0.99f else (distM / scaleMeters).toFloat().coerceIn(0.13f, 0.94f)
            val rPx = usableRadius * normR
            val blipX = cx + rPx * sin(relRad).toFloat()
            val blipY = cy - rPx * cos(relRad).toFloat()

            lastBlips.add(PlottedBlip(peer, blipX, blipY, distM, bearingDeg))

            val isSelected = peer.nodeId == selectedNodeId
            val isStale = peer.healthState(nowMs) == NodeHealthState.STALE
            val markerColor = when {
                peer.isSos -> cSos
                peer.isTransmittingAudio -> cAccent
                isStale -> cInkSecondary
                else -> cLive
            }

            if (isSelected) {
                targetLinePaint.color = if (abs(GeoMath.signedRelativeTurnDegrees(bearingDeg, deviceAzimuthDeg.toDouble())) <= 10.0) {
                    cLive
                } else {
                    cAccent
                }
                canvas.drawLine(cx, cy, blipX, blipY, targetLinePaint)
                blipRingPaint.color = cInkPrimary
                blipRingPaint.strokeWidth = 2.6f * density
                canvas.drawCircle(blipX, blipY, 13f * density, blipRingPaint)
            }

            if (peer.isTransmittingAudio && !peer.isSos) {
                blipRingPaint.color = cAccentFill
                blipRingPaint.strokeWidth = 2f * density
                canvas.drawCircle(blipX, blipY, 10.5f * density, blipRingPaint)
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
            } else {
                canvas.drawCircle(blipX, blipY, 7f * density, blipPaint)
                blipRingPaint.color = cBg
                blipRingPaint.strokeWidth = 1.6f * density
                canvas.drawCircle(blipX, blipY, 7f * density, blipRingPaint)
            }

            // Anti-overlap pill label
            val tagPrefix = if (peer.isTransmittingAudio) "BICARA " else if (isBeyondScale) ">" else ""
            val labelText = "$tagPrefix${peer.callsign.take(8)} ${GeoMath.formatDistanceCompact(distM)}"
            val textW = labelPaint.measureText(labelText)
            val padH = 5f * density
            val labelH = 16f * density

            var labelX = (blipX + 10f * density).coerceIn(6f * density, w - textW - padH * 2f - 6f * density)
            var labelY = (blipY + 4f * density).coerceIn(22f * density, h - 8f * density)

            var shiftTry = 0
            while (shiftTry < 4) {
                scratchRect.set(labelX - padH, labelY - labelH + 4f * density, labelX + textW + padH, labelY + 4f * density)
                val collides = placedLabels.any { RectF.intersects(it, scratchRect) }
                if (!collides) break
                val dir = if (shiftTry % 2 == 0) 1 else -1
                val step = ((shiftTry / 2) + 1) * (labelH + 3f * density)
                labelY = (labelY + dir * step).coerceIn(22f * density, h - 8f * density)
                shiftTry++
            }
            val finalRect = RectF(labelX - padH, labelY - labelH + 4f * density, labelX + textW + padH, labelY + 4f * density)
            placedLabels.add(finalRect)

            labelBgPaint.color = (cKeycap and 0x00FFFFFF) or 0xDD000000.toInt()
            canvas.drawRoundRect(finalRect, 4f * density, 4f * density, labelBgPaint)
            labelPaint.color = if (peer.isSos) cSos else if (peer.isTransmittingAudio) cAccent else cInkPrimary
            canvas.drawText(labelText, labelX, labelY, labelPaint)
        }

        // 6. Center "YOU" dot
        blipPaint.color = cAccentFill
        canvas.drawCircle(cx, cy, 5.5f * density, blipPaint)
        canvas.drawCircle(cx, cy, 5.5f * density, centerDotStroke)

        // 7. Top-center digital heading badge (pinned cleanly at 12 o'clock)
        val hdgInt = GeoMath.normalizeAzimuth(deviceAzimuthDeg.toDouble()).toInt()
        val cardDir = GeoMath.cardinalDirectionId(hdgInt.toDouble())
        val hdgText = "%03d° %s".format(hdgInt, cardDir)
        headingPillText.textSize = 11.5f * density
        val pillW = headingPillText.measureText(hdgText) + 18f * density
        val pillH = 20f * density
        val pillTop = max(2f * density, cy - maxRadius - 18f * density)
        scratchRect.set(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillTop + pillH)
        headingPillBg.color = cAccentFill
        canvas.drawRoundRect(scratchRect, 6f * density, 6f * density, headingPillBg)
        headingPillText.color = cInkOnAccent
        canvas.drawText(hdgText, cx, pillTop + 14.5f * density, headingPillText)

        // 8. Non-GPS standby notice only when neither local GPS nor GPS peers can plot
        if (localLat == null && peers.isNotEmpty()) {
            labelBgPaint.color = (cCanvas and 0x00FFFFFF) or 0xCC000000.toInt()
            val notice = "Menunggu kunci GPS HP ini untuk arah kompas"
            val nw = labelPaint.measureText(notice) + 18f * density
            scratchRect.set(cx - nw / 2f, cy + maxRadius * 0.42f, cx + nw / 2f, cy + maxRadius * 0.42f + 22f * density)
            canvas.drawRoundRect(scratchRect, 6f * density, 6f * density, labelBgPaint)
            labelPaint.color = cInkSecondary
            canvas.drawText(notice, cx - nw / 2f + 9f * density, cy + maxRadius * 0.42f + 15f * density, labelPaint)
        }
    }
}
