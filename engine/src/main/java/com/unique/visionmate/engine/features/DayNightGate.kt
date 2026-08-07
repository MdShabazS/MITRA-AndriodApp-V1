package com.unique.visionmate.engine.features

import android.graphics.Bitmap
import com.unique.visionmate.engine.DayNight
import com.unique.visionmate.engine.Telemetry

internal class DayNightGate(private val luminanceThreshold: Float) {

    fun evaluate(bitmap: Bitmap): DayNight {
        val metrics = computeMetrics(bitmap)
        val isDay =
            metrics.meanLuma >= luminanceThreshold ||
            metrics.p90Luma >= BRIGHT_P90_THRESHOLD ||
            metrics.brightFraction >= BRIGHT_FRACTION_THRESHOLD
        val result = if (isDay) DayNight.DAY else DayNight.NIGHT
        Telemetry.i(
            "dayNightGate mean=${"%.1f".format(metrics.meanLuma)} " +
                "p90=${"%.1f".format(metrics.p90Luma)} " +
                "brightFrac=${"%.3f".format(metrics.brightFraction)} " +
                "threshold=${"%.1f".format(luminanceThreshold)} result=$result"
        )
        return result
    }

    private fun computeMetrics(bitmap: Bitmap): LumaMetrics {
        val cropLeft = (bitmap.width * CROP_MARGIN_RATIO).toInt().coerceAtLeast(0)
        val cropTop = (bitmap.height * CROP_MARGIN_RATIO).toInt().coerceAtLeast(0)
        val cropWidth = (bitmap.width - (cropLeft * 2)).coerceAtLeast(1)
        val cropHeight = (bitmap.height - (cropTop * 2)).coerceAtLeast(1)
        val cropped = Bitmap.createBitmap(bitmap, cropLeft, cropTop, cropWidth, cropHeight)
        val gridW = minOf(GRID, cropped.width)
        val gridH = minOf(GRID, cropped.height)
        if (gridW <= 0 || gridH <= 0) return LumaMetrics(0f, 0f, 0f)
        val small = Bitmap.createScaledBitmap(cropped, gridW, gridH, true)
        val pixels = IntArray(gridW * gridH)
        small.getPixels(pixels, 0, gridW, 0, 0, gridW, gridH)
        if (small !== cropped) small.recycle()
        if (cropped !== bitmap) cropped.recycle()
        return luminanceMetricsOf(pixels)
    }

    companion object {
        private const val GRID = 32
        private const val CROP_MARGIN_RATIO = 0.15f
        private const val BRIGHT_PIXEL_THRESHOLD = 48f
        private const val BRIGHT_FRACTION_THRESHOLD = 0.08f
        private const val BRIGHT_P90_THRESHOLD = 40f

        data class LumaMetrics(
            val meanLuma: Float,
            val p90Luma: Float,
            val brightFraction: Float
        )

        /** Pure: Rec.709 luminance summary over an array of ARGB ints. */
        fun luminanceMetricsOf(pixels: IntArray): LumaMetrics {
            if (pixels.isEmpty()) return LumaMetrics(0f, 0f, 0f)
            var sum = 0.0
            var brightPixels = 0
            val lumas = FloatArray(pixels.size)
            var index = 0
            for (px in pixels) {
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b).toFloat()
                lumas[index++] = luma
                sum += luma
                if (luma >= BRIGHT_PIXEL_THRESHOLD) brightPixels++
            }
            lumas.sort()
            val p90Index = ((lumas.size - 1) * 0.90f).toInt().coerceIn(0, lumas.lastIndex)
            return LumaMetrics(
                meanLuma = (sum / pixels.size).toFloat(),
                p90Luma = lumas[p90Index],
                brightFraction = brightPixels.toFloat() / pixels.size.toFloat()
            )
        }
    }
}
