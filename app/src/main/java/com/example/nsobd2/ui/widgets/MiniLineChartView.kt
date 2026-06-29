package com.example.nsobd2.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class MiniLineChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var values: List<Float> = emptyList()
    private var maxValue: Float = 100f
    private var lineColor: Int = Color.parseColor("#4FC3F7")
    private var fillColor: Int = Color.parseColor("#334FC3F7")

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lineColor
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fillColor
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    fun setData(data: List<Float>, max: Float, color: Int) {
        this.values = data
        this.maxValue = if (max > 0) max else 1f
        this.lineColor = color
        this.fillColor = (color and 0x00FFFFFF) or 0x33000000
        linePaint.color = lineColor
        fillPaint.color = fillColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Yatay grid çizgileri (3 tane)
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        if (values.size < 2) return
        val stepX = w / (values.size - 1).coerceAtLeast(1)

        val path = Path()
        val fillPath = Path()
        for ((i, v) in values.withIndex()) {
            val x = i * stepX
            val y = h - (v / maxValue).coerceIn(0f, 1f) * h
            if (i == 0) { path.moveTo(x, y); fillPath.moveTo(x, h); fillPath.lineTo(x, y) }
            else { path.lineTo(x, y); fillPath.lineTo(x, y) }
        }
        fillPath.lineTo((values.size - 1) * stepX, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}