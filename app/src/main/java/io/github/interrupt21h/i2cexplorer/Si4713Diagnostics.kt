package io.github.interrupt21h.i2cexplorer

import java.io.IOException

/** Si4713 command/response checks. Never sends POWER_UP, TX_TUNE_FREQ or RF commands. */
interface Si47xxBus {
    fun read(address: Int, length: Int): ByteArray
    fun write(address: Int, bytes: ByteArray)
}

object Si4713Diagnostics {
    const val ADDRESS = 0x63
    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) }

    fun run(bus: Si47xxBus, address: Int = ADDRESS, clock: () -> Long = System::currentTimeMillis): DiagnosticReport {
        require(address == ADDRESS) { "This suite supports only the configured Si4713 address 0x63" }
        val results = mutableListOf<StepResult>()
        val transcript = mutableListOf<String>()
        fun test(name: String, operation: () -> Pair<ByteArray, String>) {
            try {
                val (bytes, detail) = operation()
                transcript.add("$name: RX ${hex(bytes)}")
                results.add(StepResult(name, TestStatus.INFO, bytes.map { it.toInt() and 255 }, detail))
            } catch (e: Exception) {
                transcript.add("$name: ERROR ${e.message}")
                results.add(StepResult(name, TestStatus.ERROR, emptyList(), e.message ?: "I2C transaction failed"))
            }
        }
        test("Status byte / CTS") {
            transcript.add("READ 0x63 length 1 (no command)")
            val b = bus.read(address, 1)
            if (b.size != 1) throw IOException("Short status response")
            val s = b[0].toInt() and 255
            b to "CTS=${s and 0x80 != 0}; ERR=${s and 0x40 != 0}; STCINT=${s and 0x01 != 0}; status=0x%02X".format(s)
        }
        // GET_REV (0x10) is a non-transmitting identification query. Requires powered device.
        // Do not issue while CTS is low, and never automatically POWER_UP the transmitter.
        if (results.last().status != TestStatus.ERROR && (results.last().bytes[0] and 0x80) != 0) {
            test("GET_REV firmware identification") {
                transcript.add("WRITE 0x63: 10 (GET_REV)")
                bus.write(address, byteArrayOf(0x10))
                var status = 0
                var ready = false
                repeat(10) {
                    val response = bus.read(address, 1)
                    if (response.size != 1) throw IOException("Short CTS response")
                    status = response[0].toInt() and 255
                    if ((status and 0x80) != 0) { ready = true; return@repeat }
                    Thread.sleep(10)
                }
                if (!ready) throw IOException("GET_REV CTS timeout")
                val b = bus.read(address, 9)
                if (b.size != 9) throw IOException("Short GET_REV response: ${b.size}/9")
                val s = b[0].toInt() and 255
                if ((s and 0x40) != 0) throw IOException("Si4713 command ERR flag set")
                b to "Part number=${b[1].toInt() and 255}; firmware=${b[2].toInt() and 255}.${b[3].toInt() and 255}; patch=${b[4].toInt() and 255}:${b[5].toInt() and 255}; component=${b[6].toInt() and 255}:${b[7].toInt() and 255}; chip revision=${b[8].toInt() and 255}. Confirm against Si4713 datasheet."
            }
        } else {
            results.add(StepResult("GET_REV firmware identification", TestStatus.INFO, emptyList(), "Skipped: CTS not ready or status read failed. Power-up is intentionally not automatic."))
        }
        return DiagnosticReport("si4713-live", "Si4713 status and revision", address, clock(), results, transcript)
    }
}
