package io.github.interrupt21h.i2cexplorer

import java.io.IOException

/** Bus abstraction: tests can run against fake transports in JVM unit tests. */
interface DiagnosticBus {
    fun probe(address: Int): Boolean
    fun readRegister(address: Int, register: Int, length: Int): ByteArray
    fun write(address: Int, payload: ByteArray)
}

enum class TestStatus { PASS, FAIL, INFO, ERROR }
enum class TestRisk { READ_ONLY, CHANGES_STATE }

data class TestStep(
    val label: String,
    val register: Int,
    val length: Int,
    val expected: Set<Int> = emptySet(),
    val note: String = ""
)

data class DeviceSuite(
    val id: String,
    val name: String,
    val addresses: Set<Int>,
    val steps: List<TestStep>,
    val description: String,
    val risk: TestRisk = TestRisk.READ_ONLY
)

data class StepResult(
    val name: String,
    val status: TestStatus,
    val bytes: List<Int>,
    val message: String
)

data class DiagnosticReport(
    val suiteId: String,
    val suiteName: String,
    val address: Int,
    val timestampMs: Long,
    val results: List<StepResult>,
    val transcript: List<String>
) {
    fun toJson(): String {
        fun quote(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
        return buildString {
            append("{\"schemaVersion\":1,\"suiteId\":").append(quote(suiteId))
            append(",\"suiteName\":").append(quote(suiteName))
            append(",\"address\":").append(address)
            append(",\"timestampMs\":").append(timestampMs)
            append(",\"results\":[")
            results.forEachIndexed { i, r ->
                if (i > 0) append(',')
                append("{\"name\":").append(quote(r.name))
                append(",\"status\":").append(quote(r.status.name))
                append(",\"bytes\":[").append(r.bytes.joinToString(",")).append(']')
                append(",\"message\":").append(quote(r.message)).append('}')
            }
            append("],\"transcript\":[")
            transcript.forEachIndexed { i, entry ->
                if (i > 0) append(',')
                append(quote(entry))
            }
            append("]}")
        }
    }
}

object DiagnosticCatalog {
    val suites: List<DeviceSuite> = listOf(
        DeviceSuite("bme280", "BME280 / BMP280 chip ID", setOf(0x76, 0x77),
            listOf(TestStep("Chip ID", 0xD0, 1, setOf(0x60, 0x58), "0x60=BME280; 0x58=BMP280")), "Read-only chip identification"),
        DeviceSuite("mpu6050", "MPU6050 WHO_AM_I", setOf(0x68, 0x69),
            listOf(TestStep("WHO_AM_I", 0x75, 1, setOf(0x68), "Typical MPU6050 value is 0x68")), "Read-only identification"),
        DeviceSuite("ds3231", "DS3231 status", setOf(0x68),
            listOf(TestStep("Status register", 0x0F, 1, note = "Status is not a unique chip ID")), "Read-only status inspection"),
        DeviceSuite("ads1115", "ADS1115 / ADS1015", (0x48..0x4B).toSet(),
            listOf(TestStep("Conversion register", 0x00, 2, note = "Measurement depends on input and configuration"),
                TestStep("Configuration register", 0x01, 2, note = "No unique chip ID")), "Read-only ADC register inspection"),
        DeviceSuite("ina219", "INA219 power monitor", (0x40..0x4F).toSet(),
            listOf(TestStep("Configuration", 0x00, 2, note = "Not unique identification"),
                TestStep("Bus voltage", 0x02, 2, note = "Raw value, scaling not applied")), "Read-only power monitor registers"),
        DeviceSuite("mcp23017", "MCP23017 GPIO expander", (0x20..0x27).toSet(),
            listOf(TestStep("IODIRA", 0x00, 1, note = "Assumes default BANK=0 register layout")), "Read-only direction register"),
        DeviceSuite("vl53l0x", "VL53L0X model ID", setOf(0x29),
            listOf(TestStep("Model ID", 0xC0, 1, setOf(0xEE), "Typical VL53L0X model ID is 0xEE")), "Read-only sensor ID"),
        DeviceSuite("ssd1306", "SSD1306 OLED", setOf(0x3C, 0x3D), emptyList(),
            "No reliable read-only identification. Use the separately confirmed visual display test."),
        DeviceSuite("eeprom", "24xx EEPROM", (0x50..0x57).toSet(), emptyList(),
            "EEPROM addressing varies by density; no safe generic identification read.")
    )
    fun forAddress(address: Int): List<DeviceSuite> = suites.filter { address in it.addresses }
}

class DiagnosticEngine(private val bus: DiagnosticBus, private val clock: () -> Long = System::currentTimeMillis) {
    fun run(suite: DeviceSuite, address: Int): DiagnosticReport {
        require(address in suite.addresses) { "Suite does not support address" }
        require(suite.risk == TestRisk.READ_ONLY) { "State-changing suites require a separate explicit confirmation handler" }
        val log = mutableListOf<String>()
        val results = mutableListOf<StepResult>()
        if (suite.steps.isEmpty()) {
            results.add(StepResult("Availability", TestStatus.INFO, emptyList(), suite.description))
            return DiagnosticReport(suite.id, suite.name, address, clock(), results, log)
        }
        for (step in suite.steps) {
            log.add("READ 0x%02X register 0x%02X length %d".format(address, step.register, step.length))
            try {
                val bytes = bus.readRegister(address, step.register, step.length).map { it.toInt() and 0xFF }
                if (bytes.size != step.length) throw IOException("Short read: ${bytes.size}/${step.length}")
                val status = when {
                    step.expected.isEmpty() -> TestStatus.INFO
                    bytes.size == 1 && bytes[0] in step.expected -> TestStatus.PASS
                    else -> TestStatus.FAIL
                }
                val detail = bytes.joinToString(" ") { "%02X".format(it) }
                log.add("RX $detail")
                results.add(StepResult(step.label, status, bytes, "0x$detail. ${step.note}".trim()))
            } catch (e: Exception) {
                log.add("ERROR ${e.javaClass.simpleName}: ${e.message}")
                results.add(StepResult(step.label, TestStatus.ERROR, emptyList(), e.message ?: "Transaction failed"))
            }
        }
        return DiagnosticReport(suite.id, suite.name, address, clock(), results, log)
    }
}
