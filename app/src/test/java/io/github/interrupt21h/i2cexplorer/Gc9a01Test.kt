package io.github.interrupt21h.i2cexplorer

import org.junit.Assert.assertEquals
import org.junit.Test

class Gc9a01Test {
    @Test fun dimensions() {
        assertEquals(240, Gc9a01.WIDTH)
        assertEquals(240, Gc9a01.HEIGHT)
    }
    @Test fun primaryColors() {
        assertEquals(0xF800, Gc9a01.pixel(Gc9a01.Pattern.RED, 0, 0))
        assertEquals(0x07E0, Gc9a01.pixel(Gc9a01.Pattern.GREEN, 239, 239))
        assertEquals(0x001F, Gc9a01.pixel(Gc9a01.Pattern.BLUE, 120, 120))
        assertEquals(0xFFFF, Gc9a01.pixel(Gc9a01.Pattern.WHITE, 100, 100))
        assertEquals(0, Gc9a01.pixel(Gc9a01.Pattern.BLACK, 100, 100))
    }
    @Test fun barsCoverAllSixBands() {
        val expected = intArrayOf(0xF800, 0x07E0, 0x001F, 0xFFFF, 0, 0xFFE0)
        expected.forEachIndexed { i, value ->
            assertEquals(value, Gc9a01.pixel(Gc9a01.Pattern.BARS, i * 40, 0))
            assertEquals(value, Gc9a01.pixel(Gc9a01.Pattern.BARS, i * 40 + 39, 239))
        }
    }
    @Test fun checkerboardAlternatesEveryTwelvePixels() {
        assertEquals(0xFFFF, Gc9a01.pixel(Gc9a01.Pattern.CHECKERBOARD, 0, 0))
        assertEquals(0, Gc9a01.pixel(Gc9a01.Pattern.CHECKERBOARD, 12, 0))
        assertEquals(0xFFFF, Gc9a01.pixel(Gc9a01.Pattern.CHECKERBOARD, 12, 12))
    }
    @Test fun gradientUsesRgb565Endpoints() {
        assertEquals(0, Gc9a01.pixel(Gc9a01.Pattern.GRADIENT, 0, 0))
        assertEquals(0xFFFF, Gc9a01.pixel(Gc9a01.Pattern.GRADIENT, 239, 239))
    }
    @Test fun circlesMaskOutsideRoundPanel() {
        assertEquals(0, Gc9a01.pixel(Gc9a01.Pattern.CIRCLES, 0, 0))
        assertEquals(0xFFFF, Gc9a01.pixel(Gc9a01.Pattern.CIRCLES, 120, 120))
    }
}
