package com.example

import android.graphics.Bitmap
import android.graphics.Color

object ImageUtils {
    fun removeGreenScreen(original: Bitmap): Bitmap {
        val width = original.width
        val height = original.height
        val mutableBitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(width * height)
        mutableBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)

            // Extremely simple chroma keying for perfect green (#00FF00) generated images
            // If green is dominant and red/blue are low
            if (g > 150 && r < 100 && b < 100) {
                pixels[i] = Color.TRANSPARENT
            } else if (g > 130 && r < 120 && b < 120) {
                // edge smoothing/anti-aliasing
                pixels[i] = Color.argb(128, r, g, b)
            }
        }
        
        mutableBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return mutableBitmap
    }
}
