package com.example

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class VisualizerBubbleView(context: Context) : View(context) {
    private var magnitudes = FloatArray(128)
    private var isPlaying = false
    private var idlePhase = 0f
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 15f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }

    private val colors = intArrayOf(
        Color.parseColor("#FF0055"), // Pink/Red
        Color.parseColor("#FF00FF"), // Magenta
        Color.parseColor("#0055FF"), // Blue
        Color.parseColor("#00FFFF"), // Cyan
        Color.parseColor("#00FF55"), // Green
        Color.parseColor("#FFFF00"), // Yellow
        Color.parseColor("#FF5500"), // Orange
        Color.parseColor("#FF0055")  // back to Pink/Red
    )

    fun updateVisualizer(fft: ByteArray?) {
        if (fft == null || fft.isEmpty()) {
            isPlaying = false
            invalidate()
            return
        }
        
        var maxMag = 0f
        val n = Math.min(fft.size / 2, 128)
        for (i in 0 until n) {
            val real = fft[i * 2].toFloat()
            val imag = fft[i * 2 + 1].toFloat()
            val mag = kotlin.math.sqrt(real * real + imag * imag)
            magnitudes[i] = mag
            if (mag > maxMag) maxMag = mag
        }
        
        isPlaying = maxMag > 15f
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = (width.coerceAtMost(height) / 2f) * 0.45f
        
        // Rotate gradient slowly
        val matrix = Matrix()
        matrix.postRotate(idlePhase * 15f, cx, cy)
        val sweepGradient = SweepGradient(cx, cy, colors, null)
        sweepGradient.setLocalMatrix(matrix)
        
        paint.shader = sweepGradient
        glowPaint.shader = sweepGradient
        
        if (isPlaying) {
            val path = Path()
            val points = 60 // 60 points for smoothing
            val angleStep = Math.PI * 2 / points
            
            for (i in 0..points) {
                val idx = i % points
                // Average some magnitudes for smoother spikes
                val magIndex = (idx * 2).coerceAtMost(magnitudes.size - 1)
                var mag = magnitudes[magIndex] * 0.6f
                mag = mag.coerceIn(0f, baseRadius * 0.8f) // limit spike height
                
                val radius = baseRadius + mag
                
                val angle = i * angleStep - Math.PI / 2
                val x = cx + cos(angle).toFloat() * radius
                val y = cy + sin(angle).toFloat() * radius
                
                if (i == 0) path.moveTo(x, y)
                else path.lineTo(x, y)
            }
            path.close()
            
            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, paint)
            
            idlePhase += 0.2f
            postInvalidateOnAnimation()
        } else {
            // Idle breathing animation
            idlePhase += 0.03f
            val breath = (sin(idlePhase.toDouble()).toFloat() + 1f) / 2f // 0 to 1
            val radius = baseRadius + breath * 6f
            
            val path = Path()
            path.addCircle(cx, cy, radius, Path.Direction.CW)
            
            canvas.drawPath(path, glowPaint)
            canvas.drawPath(path, paint)
            
            postInvalidateOnAnimation()
        }
    }
}
