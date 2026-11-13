package io.github.interrupt21h.i2cexplorer

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DiagnosticEngineTest {
    private class FakeBus(private val values: Map<Pair<Int, Int>, ByteArray>) : DiagnosticBus {
        override fun probe(address: Int) = true
        override fun readRegister(address: Int, register: Int, length: Int): ByteArray =
            values[address to register] ?: throw IOException("No device")
        override fun write(address: Int, payload: ByteArray) = error("Unexpected write")
    }
    private val bme = DiagnosticCatalog.suites.first { it.id == "bme280" }

    @Test fun identifiesBme280ChipId() {
        val report = DiagnosticEngine(FakeBus(mapOf((0x76 to 0xD0) to byteArrayOf(0x60))), { 123L }).run(bme, 0x76)
        assertEquals(TestStatus.PASS, report.results.single().status)
        assertEquals(123L, report.timestampMs)
        assertTrue(report.toJson().contains("\"status\":\"PASS\""))
    }
    @Test fun rejectsUnknownId() {
        val report = DiagnosticEngine(FakeBus(mapOf((0x76 to 0xD0) to byteArrayOf(0x42)))).run(bme, 0x76)
        assertEquals(TestStatus.FAIL, report.results.single().status)
    }
    @Test fun reportsTransportFailure() {
        val report = DiagnosticEngine(FakeBus(emptyMap())).run(bme, 0x76)
        assertEquals(TestStatus.ERROR, report.results.single().status)
        assertTrue(report.transcript.any { it.startsWith("ERROR") })
    }
    @Test fun reportsUnsupportedReadOnlyProbeAsInfo() {
        val oled = DiagnosticCatalog.suites.first { it.id == "ssd1306" }
        val report = DiagnosticEngine(FakeBus(emptyMap())).run(oled, 0x3C)
        assertEquals(TestStatus.INFO, report.results.single().status)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongAddress() {
        DiagnosticEngine(FakeBus(emptyMap())).run(bme, 0x40)
    }
}
