package io.github.interrupt21h.i2cexplorer

import org.junit.Assert.*
import org.junit.Test

class OledDiagnosticsTest {
    @Test fun correctFrameSizes() {
        assertEquals(512, OledDiagnostics.image(128, 32, OledDiagnostics.Pattern.CHECKERBOARD).size)
        assertEquals(1024, OledDiagnostics.image(128, 64, OledDiagnostics.Pattern.CHECKERBOARD).size)
    }
    @Test fun checkerboardAlternatesByEightColumnsAndPage() {
        val image = OledDiagnostics.image(128, 32, OledDiagnostics.Pattern.CHECKERBOARD)
        assertEquals(255, image[0].toInt() and 255)
        assertEquals(0, image[8].toInt() and 255)
        assertEquals(0, image[128].toInt() and 255)
        assertEquals(255, image[136].toInt() and 255)
    }
    @Test fun blankIsBlank() {
        assertTrue(OledDiagnostics.image(128, 32, OledDiagnostics.Pattern.ALL_OFF).all { it == 0.toByte() })
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedGeometry() {
        OledDiagnostics.image(128, 48, OledDiagnostics.Pattern.ALL_ON)
    }
}
