package com.sanepo.offgrid.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.sanepo.offgrid.R
import kotlin.math.max
import kotlin.math.sin

class WaveformMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 24
    private val barLevels = FloatArray(barCount)
    private var activeLevel = 0f
    private var isTxMode = false
    private var isRxMode = false
    private var phase = 0f

    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    fun updateAudioState(levelNormalized: Float, txActive: Boolean, rxActive: Boolean) {
        activeLevel = levelNormalized.coerceIn(0f, 1f)
        isTxMode = txActive
        isRxMode = rxActive
        phase += 0.45f
        for (i in 0 until barCount) {
            val target = if (!txActive && !rxActive) {
                0.08f
            } else {
                val centerDist = kotlin.math.abs(i - (barCount - 1) / 2f) / (barCount / 2f)
                val envelope = (1f - centerDist * 0.55f).coerceIn(0.25f, 1f)
                val wave = (sin((phase + i * 0.52).toDouble()).toFloat() * 0.35f + 0.65f)
                max(0.10f, activeLevel * envelope * wave)
            }
            barLevels[i] = barLevels[i] * 0.45f + target * 0.55f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val colorRes = when {
            isTxMode -> R.color.accent_fill
            isRxMode -> R.color.status_live
            else -> R.color.stroke_instrument
        }
        activePaint.color = ContextCompat.getColor(context, colorRes)
        idlePaint.color = ContextCompat.getColor(context, R.color.stroke_subtle)

        val step = w / barCount
        val barWidth = step * 0.58f
        val cy = h / 2f
        val maxHalf = (h * 0.44f)

        for (i in 0 until barCount) {
            val level = barLevels[i].coerceIn(0.08f, 1f)
            val halfH = max(3f, maxHalf * level)
            val left = i * step + (step - barWidth) / 2f
            rect.set(left, cy - halfH, left + barWidth, cy + halfH)
            val paint = if (isTxMode || isRxMode) activePaint else idlePaint
            canvas.drawRoundRect(rect, 3f, 3f, paint)
        }
    }
}
