package com.github.swerchansky.vkturnproxy.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class NetworkGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val maxPoints = 30

    private val uploadHistory = ArrayDeque<Float>()
    private val downloadHistory = ArrayDeque<Float>()

    private val uploadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#43A047")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val downloadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#42A5F5")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fillUpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2043A047")
        style = Paint.Style.FILL
    }
    private val fillDownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2042A5F5")
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22FFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88FFFFFF")
        textSize = 24f
    }

    fun addDataPoint(uploadPps: Float, downloadPps: Float) {
        if (uploadHistory.size >= maxPoints) uploadHistory.removeFirst()
        if (downloadHistory.size >= maxPoints) downloadHistory.removeFirst()
        uploadHistory.addLast(uploadPps)
        downloadHistory.addLast(downloadPps)
        invalidate()
    }

    fun clear() {
        uploadHistory.clear()
        downloadHistory.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (uploadHistory.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()
        val padding = 4f
        val graphH = h - padding * 2

        val maxVal = maxOf(
            uploadHistory.maxOrNull() ?: 1f,
            downloadHistory.maxOrNull() ?: 1f,
            1f,
        )

        // Draw horizontal grid lines
        for (i in 1..3) {
            val y = padding + graphH * i / 4
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        val stepX = w / (maxPoints - 1).toFloat()

        fun buildPath(data: ArrayDeque<Float>, fill: Boolean): Path {
            val path = Path()
            val startIdx = maxPoints - data.size
            data.forEachIndexed { i, v ->
                val x = (startIdx + i) * stepX
                val y = padding + graphH * (1f - v / maxVal)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (fill) {
                val lastX = (startIdx + data.size - 1) * stepX
                path.lineTo(lastX, h)
                path.lineTo(startIdx * stepX, h)
                path.close()
            }
            return path
        }

        // Fill areas
        canvas.drawPath(buildPath(uploadHistory, true), fillUpPaint)
        canvas.drawPath(buildPath(downloadHistory, true), fillDownPaint)

        // Draw lines
        canvas.drawPath(buildPath(uploadHistory, false), uploadPaint)
        canvas.drawPath(buildPath(downloadHistory, false), downloadPaint)

        // Legend
        canvas.drawText("↑", padding, h - padding, labelPaint.apply { color = Color.parseColor("#43A047") })
        canvas.drawText("↓", padding + 30f, h - padding, labelPaint.apply { color = Color.parseColor("#42A5F5") })
    }
}
