package com.unique.visionmate.engine

import com.unique.visionmate.engine.features.DayNightGate
import org.junit.Assert.assertEquals
import org.junit.Test

class DayNightGateTest {

    private fun meanLuma(pixels: IntArray): Float = DayNightGate.luminanceMetricsOf(pixels).meanLuma

    @Test
    fun luminanceOf_emptyArray_returnsZero() {
        assertEquals(0f, meanLuma(IntArray(0)), 0.001f)
    }

    @Test
    fun luminanceOf_blackPixels_returnsZero() {
        val pixels = IntArray(16) { 0xFF000000.toInt() }
        assertEquals(0f, meanLuma(pixels), 0.001f)
    }

    @Test
    fun luminanceOf_whitePixels_returns255() {
        val pixels = IntArray(16) { 0xFFFFFFFF.toInt() }
        assertEquals(255f, meanLuma(pixels), 0.5f)
    }

    @Test
    fun luminanceOf_pureRed_isRec709Weight() {
        // 0.2126 * 255 = 54.213
        val pixels = IntArray(1) { 0xFFFF0000.toInt() }
        assertEquals(54.2f, meanLuma(pixels), 0.5f)
    }

    @Test
    fun luminanceOf_pureGreen_isRec709Weight() {
        // 0.7152 * 255 = 182.376
        val pixels = IntArray(1) { 0xFF00FF00.toInt() }
        assertEquals(182.4f, meanLuma(pixels), 0.5f)
    }

    @Test
    fun luminanceOf_pureBlue_isRec709Weight() {
        // 0.0722 * 255 = 18.411
        val pixels = IntArray(1) { 0xFF0000FF.toInt() }
        assertEquals(18.4f, meanLuma(pixels), 0.5f)
    }

    @Test
    fun luminanceOf_grayMidpoint_returnsHalf() {
        // ARGB 0xFF808080 = mid-gray at 128/255
        val pixels = IntArray(8) { 0xFF808080.toInt() }
        assertEquals(128f, meanLuma(pixels), 0.5f)
    }
}
