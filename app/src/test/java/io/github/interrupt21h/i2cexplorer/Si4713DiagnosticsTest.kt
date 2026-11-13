package io.github.interrupt21h.i2cexplorer

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class Si4713DiagnosticsTest {
    private class FakeBus(
        private val status: Int = 0x80,
        private val revision: ByteArray = byteArrayOf(0x80.toByte(), 0x0D, 0x01, 0x02, 0, 0, 0, 0, 1),
        private val fail: Boolean = false
    ) : Si47xxBus {
        val writes = mutableListOf<List<Int>>()
        val reads = mutableListOf<Int>()
        override fun read(address: Int, length: Int): ByteArray {
            assertEquals(0x63, address)
            reads.add(length)
            if (fail) throw IOException("USB disconnected")
            return if (length == 1) byteArrayOf(status.toByte()) else revision
        }
        override fun write(address: Int, bytes: ByteArray) {
            assertEquals(0x63, address)
            writes.add(bytes.map { it.toInt() and 255 })
        }
    }

    @Test fun readyDeviceReadsRevisionWithoutPoweringUp() {
        val bus = FakeBus()
        val report = Si4713Diagnostics.run(bus, clock = { 123L })
        assertEquals(123L, report.timestampMs)
        assertEquals(2, report.results.size)
        assertEquals(TestStatus.INFO, report.results[0].status)
        assertEquals(TestStatus.INFO, report.results[1].status)
        assertEquals(listOf(listOf(0x10)), bus.writes)
        assertEquals(listOf(1, 1, 9), bus.reads)
        assertTrue(report.toJson().contains("si4713-live"))
        assertTrue(report.transcript.any { it.contains("GET_REV") })
    }

    @Test fun notReadySkipsCommandAndReportsReason() {
        val bus = FakeBus(status = 0)
        val report = Si4713Diagnostics.run(bus)
        assertTrue(bus.writes.isEmpty())
        assertTrue(report.results[1].message.contains("Skipped"))
    }

    @Test fun transportErrorProducesStructuredResults() {
        val bus = FakeBus(fail = true)
        val report = Si4713Diagnostics.run(bus)
        assertEquals(TestStatus.ERROR, report.results[0].status)
        assertTrue(bus.writes.isEmpty())
        assertEquals(2, report.results.size)
    }

    @Test fun commandErrorFlagIsReported() {
        val bus = FakeBus(revision = byteArrayOf(0xC0.toByte(), 0, 0, 0, 0, 0, 0, 0, 0))
        val report = Si4713Diagnostics.run(bus)
        assertEquals(TestStatus.ERROR, report.results[1].status)
    }

    @Test fun shortRevisionIsRejected() {
        val bus = FakeBus(revision = byteArrayOf(0x80.toByte(), 0))
        val report = Si4713Diagnostics.run(bus)
        assertEquals(TestStatus.ERROR, report.results[1].status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpectedAddress() {
        Si4713Diagnostics.run(FakeBus(), address = 0x62)
    }
}
