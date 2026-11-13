package io.github.interrupt21h.i2cexplorer

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.concurrent.thread

private const val USB_PERMISSION_ACTION = "io.github.interrupt21h.i2cexplorer.GRANT_USB"
private val BG = Color.rgb(12, 18, 29)
private val PANEL = Color.rgb(24, 34, 50)
private val ACCENT = Color.rgb(82, 190, 240)
private val FG = Color.rgb(239, 246, 255)
private val MUTED = Color.rgb(159, 178, 200)
private val GREEN = Color.rgb(103, 224, 174)

data class Profile(val name: String, val category: String, val addresses: Set<Int>, val note: String)
private val profiles = listOf(
    Profile("Si4713", "FM transmitter", setOf(0x63), "Silicon Labs command/response protocol; address depends on SEN configuration"),
    Profile("Si4703", "FM receiver", setOf(0x10), "Silicon Labs radio; uses special register access sequence"),
    Profile("SSD1306 128×64 OLED (HiLetgo 0.96-inch)", "I²C OLED display", setOf(0x3C, 0x3D), "Four-pin GND/VCC/SCL/SDA module. ACK confirms only the address; panel size and controller cannot be determined by scan."),
    Profile("SSD1306 128×32 OLED (DSD TECH 0.91-inch)", "I²C OLED display", setOf(0x3C, 0x3D), "Four-pin GND/VCC/SCL/SDA module. Often 0x3C. 128×32 is a configuration choice, not an identifiable I²C property."),
    Profile("SH1106 OLED", "I²C OLED display", setOf(0x3C, 0x3D), "Shares addresses and command similarities with SSD1306; do not assume the controller."),
    Profile("BME280 / BMP280", "Environmental sensor", setOf(0x76, 0x77), "Check read-only chip ID register 0xD0"),
    Profile("SHT3x", "Temperature / humidity", setOf(0x44, 0x45), "Command-based sensor"),
    Profile("ADS1115 / ADS1015", "ADC", (0x48..0x4B).toSet(), "Configurable address; no universal chip ID"),
    Profile("MCP23017 / MCP23008", "GPIO expander", (0x20..0x27).toSet(), "Register-mapped GPIO family"),
    Profile("PCF8574", "GPIO expander", ((0x20..0x27) + (0x38..0x3F)).toSet(), "Shares addresses with other expanders"),
    Profile("DS3231 / DS1307", "Real-time clock", setOf(0x68), "Read-only time registers can help distinguish RTCs"),
    Profile("MPU6050 / MPU9250", "Motion sensor", setOf(0x68, 0x69), "WHO_AM_I register varies by chip"),
    Profile("24xx EEPROM", "EEPROM", (0x50..0x57).toSet(), "Avoid writes; addressing varies by memory size"),
    Profile("INA219 / INA226", "Power monitor", (0x40..0x4F).toSet(), "Several compatible and unrelated devices share this range"),
    Profile("PCA9685", "PWM controller", setOf(0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F), "Configurable address; may respond to broadcast address"),
    Profile("VL53L0X", "Distance sensor", setOf(0x29), "Default address may be changed by firmware"),
    Profile("AHT20 / AHT21", "Temperature / humidity", setOf(0x38), "Command-oriented sensor"),
    Profile("LAFVIN 2.13-inch 250×122 e-paper HAT", "SPI display (not I²C)", emptySet(), "Uses DIN, CLK, CS, DC, RST and BUSY; not detectable by I²C scanning. Needs an explicit SPI profile and control pins.")
)


// Declarative diagnostics: no arbitrary code execution and no writes by default.
private data class Diagnostic(val name: String, val address: Int, val register: Int, val length: Int, val description: String)
private val diagnostics = listOf(
    Diagnostic("BME280 / BMP280 chip ID", 0x76, 0xD0, 1, "0x60 BME280; 0x58 BMP280"),
    Diagnostic("BME280 / BMP280 chip ID (alternate)", 0x77, 0xD0, 1, "0x60 BME280; 0x58 BMP280"),
    Diagnostic("MPU6050 WHO_AM_I", 0x68, 0x75, 1, "Usually 0x68"),
    Diagnostic("MPU6050 WHO_AM_I (alternate)", 0x69, 0x75, 1, "Usually 0x68"),
    Diagnostic("INA219 configuration", 0x40, 0x00, 2, "Configuration read; not unique identification"),
    Diagnostic("ADS1115 conversion register", 0x48, 0x00, 2, "ADC conversion value; not unique identification"),
    Diagnostic("MCP23017 IODIRA", 0x20, 0x00, 1, "GPIO direction register; not unique identification"),
    Diagnostic("DS3231 status", 0x68, 0x0F, 1, "Status flags; shared address with motion sensors")
)

class ExplorerActivity : Activity() {
    private lateinit var usb: UsbManager
    private lateinit var root: LinearLayout
    private lateinit var page: LinearLayout
    private lateinit var status: TextView
    private var driver: Ft232h? = null
    private var demo = true
    private var busy = false
    private var selected = 0x63
    private var tab = 0
    private var detected = setOf(0x10, 0x3C, 0x48, 0x63, 0x68, 0x76)
    private var scanComplete = false
    private val handler = Handler(Looper.getMainLooper())
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                USB_PERMISSION_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) connect(device)
                    else { toast("USB permission denied"); render() }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    driver?.close(); driver = null; demo = true; scanComplete = false; render()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        usb = getSystemService(USB_SERVICE) as UsbManager
        val filter = IntentFilter().apply {
            addAction(USB_PERMISSION_ACTION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        selected = savedInstanceState?.getInt("selected", 0x63) ?: 0x63
        tab = savedInstanceState?.getInt("tab", 0) ?: 0
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selected", selected); outState.putInt("tab", tab)
        super.onSaveInstanceState(outState)
    }
    private fun dp(n: Int) = (resources.displayMetrics.density * n + 0.5f).toInt()
    private fun rounded(color: Int, radius: Int = 16): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun label(text: String, size: Float = 14f, color: Int = FG, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
    private fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun gap(parent: LinearLayout, h: Int = 12) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h))) }
    private fun panel(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        val box = column().apply { background = rounded(PANEL); setPadding(dp(16), dp(16), dp(16), dp(16)) }
        box.addView(label(title, 17f, FG, true)); gap(box, 12); build(box)
        parent.addView(box, LinearLayout.LayoutParams(-1, -2)); gap(parent, 14)
    }
    private fun action(text: String, enabled: Boolean = true, onClick: () -> Unit): TextView =
        label(text, 14f, if (enabled) BG else MUTED, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(if (enabled) ACCENT else Color.rgb(54, 69, 87), 12)
            isEnabled = enabled; setOnClickListener { onClick() }
        }
    private fun secondary(text: String, onClick: () -> Unit): TextView =
        label(text, 14f, FG, true).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(46, 63, 83), 12)
            setOnClickListener { onClick() }
        }
    private fun render() {
        root = column().apply {
            setBackgroundColor(BG)
            // Android 15+ enforces edge-to-edge for apps targeting API 35.
            // Keep all controls below the status bar and above gesture navigation.
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                    )
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom
                    )
                }
                insets
            }
        }
        val heading = column().apply { setPadding(dp(20), dp(24), dp(20), dp(16)) }
        heading.addView(label("I²C EXPLORER", 12f, ACCENT, true))
        gap(heading, 5)
        heading.addView(label("FT232H Bus Lab", 26f, FG, true))
        gap(heading, 7)
        status = label(if (demo) "● DEMO MODE  ·  Simulated results" else "● FT232H CONNECTED  ·  USB Host", 12f, if (demo) MUTED else GREEN, true)
        heading.addView(status)
        root.addView(heading)
        val nav = row().apply { setPadding(dp(12), 0, dp(12), dp(12)) }
        listOf("Bus scan", "Devices", "Guide").forEachIndexed { index, title ->
            val t = label(title, 14f, if (tab == index) ACCENT else MUTED, tab == index).apply {
                gravity = Gravity.CENTER; setPadding(dp(8), dp(12), dp(8), dp(12))
                background = rounded(if (tab == index) PANEL else BG, 10)
                setOnClickListener { tab = index; render() }
            }
            nav.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        page = column().apply { setPadding(dp(16), dp(4), dp(16), dp(24)) }
        scroll.addView(page); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        when (tab) { 0 -> scanPage(); 1 -> devicePage(); else -> guidePage() }
    }
    private fun scanPage() {
        panel(page, "Connection") { p ->
            p.addView(label(if (demo) "Preview the interface without hardware. Demo addresses are fictional test data." else "Connected to FT232H USB interface.", 13f, MUTED))
            gap(p)
            val buttons = row()
            buttons.addView(action("Connect USB", !busy) { discover() }, LinearLayout.LayoutParams(0, -2, 1f))
            buttons.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
            buttons.addView(secondary("Demo mode") { driver?.close(); driver = null; demo = true; scanComplete = false; detected = setOf(0x10,0x3C,0x48,0x63,0x68,0x76); render() }, LinearLayout.LayoutParams(0, -2, 1f))
            p.addView(buttons)
        }
        panel(page, "Bus overview") { p ->
            val summary = row()
            listOf("${detected.size}\nAddresses", "7-bit\nI²C mode", if (demo) "SIM\nSource" else "USB\nSource").forEach {
                val t = label(it, 14f, FG, true).apply { gravity = Gravity.CENTER }
                summary.addView(t, LinearLayout.LayoutParams(0, dp(54), 1f))
            }
            p.addView(summary); gap(p)
            p.addView(action(if (busy) "Scanning…" else "Scan bus  ·  0x08–0x77", !busy && !demo && driver != null) { scan() })
            gap(p, 8)
            p.addView(label("Address-only ACK probe. Some devices may react to probing; use only on a bus you control.", 12f, MUTED))
        }
        panel(page, "Address map") { p ->
            p.addView(label("Green = ACK    ·    Blue = selected    ·    Gray = no response", 12f, MUTED))
            gap(p, 12)
            for (base in 0x08..0x77 step 8) {
                val r = row()
                for (a in base until minOf(base + 8, 0x78)) {
                    val active = a in detected
                    val cell = label("%02X".format(Locale.US, a), 12f, if (selected == a) BG else if (active) GREEN else MUTED, active).apply {
                        gravity = Gravity.CENTER; setPadding(0, dp(11), 0, dp(11))
                        background = rounded(if (selected == a) ACCENT else if (active) Color.rgb(30, 74, 68) else Color.rgb(31, 43, 59), 7)
                        setOnClickListener { selected = a; tab = 1; render() }
                    }
                    val lp = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
                    r.addView(cell, lp)
                }
                p.addView(r)
            }
        }
    }
    private fun devicePage() {
        panel(page, "Address inspector") { p ->
            p.addView(label("Tap an address to explore possible device families. An ACK alone does not identify a chip.", 13f, MUTED))
            gap(p)
            val spinner = Spinner(this)
            val addresses = (0x08..0x77).toList()
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, addresses.map { "0x%02X".format(it) })
            spinner.setSelection((selected - 0x08).coerceIn(0, addresses.lastIndex))
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val a = addresses[position]
                    if (selected != a) { selected = a; renderDeviceMatches() }
                }
            }
            p.addView(spinner)
            gap(p, 6)
            p.addView(label(if (selected in detected) "● ACK observed ${if (demo) "(simulated)" else "(live scan)"}" else "○ No ACK recorded", 13f, if (selected in detected) GREEN else MUTED))
        }
        matches = column()
        page.addView(matches)
        renderDeviceMatches()
    }
    private var lastReport: DiagnosticReport? = null
    private var matches: LinearLayout? = null
    private fun renderDeviceMatches() {
        val host = matches ?: return
        host.removeAllViews()
        val candidates = profiles.filter { selected in it.addresses }
        panel(host, "Possible device families (${candidates.size})") { p ->
            if (candidates.isEmpty()) p.addView(label("No built-in profile for this address. The address may still be valid.", 13f, MUTED))
            candidates.forEachIndexed { index, profile ->
                if (index > 0) gap(p, 14)
                p.addView(label(profile.name, 16f, FG, true))
                p.addView(label(profile.category + " · address-based suggestion only", 12f, ACCENT))
                gap(p, 3)
                p.addView(label(profile.note, 13f, MUTED))
            }
        }
        if (selected == 0x63) panel(host, "Si4713 live diagnostics") { p ->
            p.addView(label("Status/CTS and GET_REV firmware response. Does not enable RF transmission or power up the chip. Requires a correctly powered Si4713; read commands can affect command state.", 13f, MUTED))
            gap(p, 8)
            p.addView(action("Run detailed Si4713 tests", !busy && !demo && driver != null) { confirmSi4713Test() })
        }
        panel(host, "Device-specific diagnostics") { p ->
            val tests = diagnostics.filter { it.address == selected }
            if (tests.isEmpty()) p.addView(label("No read-only register recipe for this address. Command-based devices require a dedicated protocol.", 13f, MUTED))
            if (selected == 0x3C || selected == 0x3D) {
                p.addView(label("SSD1306-compatible OLED display toggle", 14f, FG, true)); gap(p, 4)
                p.addView(label("Choose 128×32 or 128×64 and draw checkerboard, stripes, or a blank screen. Also supports OFF/ON. Writes to the display; confirmation required.", 12f, MUTED)); gap(p, 8)
                p.addView(action("Run OLED display test", !busy && !demo && driver != null) { confirmOledTest() }); gap(p, 12)
            }
            tests.forEach { test ->
                p.addView(label(test.name, 14f, FG, true)); gap(p, 4)
                p.addView(label(test.description, 12f, MUTED)); gap(p, 8)
                p.addView(action("Read register 0x%02X".format(test.register), !busy && !demo && driver != null) { runDiagnostic(test) }); gap(p, 12)
            }
        }
        panel(host, "Reusable test suites") { p ->
            val suites = DiagnosticCatalog.forAddress(selected)
            if (suites.isEmpty()) p.addView(label("No test suites at this address.", 13f, MUTED))
            suites.forEach { suite ->
                p.addView(label(suite.name, 15f, FG, true))
                gap(p, 4)
                p.addView(label(suite.description, 12f, MUTED))
                gap(p, 8)
                p.addView(action(if (suite.steps.isEmpty()) "View test availability" else "Run read-only suite",
                    !busy && (suite.steps.isEmpty() || (!demo && driver != null))) { executeSuite(suite) })
                gap(p, 12)
            }
            lastReport?.let { report ->
                if (report.address == selected) {
                    p.addView(secondary("View last test results") { showReport(report) })
                }
            }
        }
        panel(host, "Identification policy") { p ->
            p.addView(label("Automatic identification is intentionally conservative. No registers are read or written during address scans. Device-specific read-only probes will be added after hardware validation.", 13f, MUTED))
        }
    }
    private fun guidePage() {
        panel(page, "Wiring") { p ->
            listOf("D0  →  SCL", "D1 + D2  →  SDA (tie together)", "GND  →  target GND", "SCL and SDA need suitable pull-up resistors", "Check target logic voltage before connecting").forEach { p.addView(label(it, 14f)); gap(p, 9) }
        }
        panel(page, "Compatibility") { p ->
            p.addView(label("FT232H MPSSE GPIO-driven I²C. Clock stretching and multi-master arbitration are unsupported. USB timing and electrical behavior require physical testing.", 13f, MUTED))
        }
        panel(page, "Your display modules") { p ->
            p.addView(label("HiLetgo 0.96-inch 128×64 OLED: I²C, usually 0x3C/0x3D.", 13f, FG)); gap(p, 8)
            p.addView(label("DSD TECH 0.91-inch 128×32 OLED: I²C, usually 0x3C/0x3D.", 13f, FG)); gap(p, 8)
            p.addView(label("LAFVIN 2.13-inch e-paper HAT: SPI; will never appear in an I²C scan.", 13f, FG)); gap(p, 8)
            p.addView(label("Two OLEDs with the same address cannot share a bus without an I²C multiplexer or address reconfiguration.", 13f, MUTED))
        }
        panel(page, "Device catalog") { p ->
            p.addView(label("${profiles.size} profiles for common sensors, radios, ADCs, expanders, displays, memories and controllers. Multiple candidate profiles may share the same address.", 13f, MUTED))
        }
    }
    private fun discover() {
        val device = usb.deviceList.values.firstOrNull { it.vendorId == 0x0403 && it.productId == 0x6014 }
        if (device == null) { toast("No FT232H (0403:6014) found"); return }
        if (!usb.hasPermission(device)) {
            val intent = PendingIntent.getBroadcast(this, 0, Intent(USB_PERMISSION_ACTION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            usb.requestPermission(device, intent)
        } else connect(device)
    }
    private fun connect(device: UsbDevice) {
        busy = true; render()
        thread {
            try {
                val d = Ft232h(usb, device)
                handler.post {
                    driver?.close(); driver = d; demo = false; busy = false
                    detected = emptySet(); scanComplete = false; render()
                }
            } catch (e: Exception) {
                handler.post { busy = false; toast("Connection failed: ${e.message}"); render() }
            }
        }
    }
    private fun scan() {
        val d = driver ?: return
        busy = true; detected = emptySet(); render()
        thread {
            val results = mutableSetOf<Int>()
            try {
                for (address in 0x08..0x77) {
                    if (d.probe(address)) results.add(address)
                    if (address % 8 == 0 || address == 0x77) handler.post {
                        detected = results.toSet()
                        status.text = "● Scanning address 0x%02X…".format(address)
                    }
                }
                handler.post { detected = results.toSet(); busy = false; scanComplete = true; render() }
            } catch (e: Exception) {
                handler.post { busy = false; toast("Scan error: ${e.message}"); render() }
            }
        }
    }
    private fun confirmSi4713Test() {
        if (busy || demo || driver == null) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Si4713 hardware diagnostics")
            .setMessage("Run status and GET_REV commands at 0x63? These are non-transmitting tests but send a command and require the chip to be powered and ready. No POWER_UP or TX commands are sent.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run tests") { _, _ ->
                val d = driver ?: return@setPositiveButton
                busy = true
                render()
                thread {
                    val bus = object : Si47xxBus {
                        override fun read(address: Int, length: Int) = d.readRaw(address, length)
                        override fun write(address: Int, bytes: ByteArray) = d.writeCommand(address, bytes)
                    }
                    val report = try { Si4713Diagnostics.run(bus) } catch (e: Exception) {
                        DiagnosticReport("si4713-live", "Si4713 diagnostics", 0x63, System.currentTimeMillis(),
                            listOf(StepResult("Execution", TestStatus.ERROR, emptyList(), e.message ?: "Unknown failure")), emptyList())
                    }
                    handler.post {
                        busy = false
                        lastReport = report
                        render()
                        showReport(report)
                    }
                }
            }.show()
    }
    private fun confirmOledTest() {
        if (driver == null || demo || busy) return
        val choices = arrayOf(
            "128×32 · checkerboard",
            "128×32 · vertical stripes",
            "128×32 · clear screen",
            "128×64 · checkerboard",
            "128×64 · vertical stripes",
            "128×64 · clear screen",
            "Display OFF → ON (no framebuffer change)"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("SSD1306-compatible OLED tests")
            .setItems(choices) { _, choice ->
                android.app.AlertDialog.Builder(this)
                    .setTitle("Confirm hardware test")
                    .setMessage("Only proceed with a known SSD1306-compatible OLED at 0x%02X. The test writes commands/data, may take time on the experimental USB driver, and may not work on SH1106 modules.".format(selected))
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Run test") { _, _ -> runOledTest(choice) }
                    .show()
            }.show()
    }
    private fun runOledTest(choice: Int) {
        val d = driver ?: return
        if (busy || demo) return
        val address = selected
        busy = true
        render()
        thread {
            try {
                if (choice == 6) {
                    d.writeCommand(address, byteArrayOf(0x00, 0xAE.toByte()))
                    Thread.sleep(350)
                    d.writeCommand(address, byteArrayOf(0x00, 0xAF.toByte()))
                } else {
                    val height = if (choice < 3) 32 else 64
                    val pattern = when (choice % 3) {
                        0 -> OledDiagnostics.Pattern.CHECKERBOARD
                        1 -> OledDiagnostics.Pattern.VERTICAL_STRIPES
                        else -> OledDiagnostics.Pattern.ALL_OFF
                    }
                    OledDiagnostics.draw(d, address, height, pattern)
                }
                handler.post {
                    busy = false
                    toast("Commands sent. Verify the OLED visually; device identity is unconfirmed.")
                    render()
                }
            } catch (e: Exception) {
                handler.post { busy = false; toast("OLED test failed: ${e.message}"); render() }
            }
        }
    }
    private fun runDiagnostic(test: Diagnostic) {
        val d = driver ?: return
        if (busy || demo) return
        busy = true
        thread {
            try {
                val data = d.readRegister(test.address, test.register, test.length)
                val hex = data.joinToString(" ") { "%02X".format(it.toInt() and 255) }
                handler.post {
                    busy = false
                    android.app.AlertDialog.Builder(this)
                        .setTitle(test.name)
                        .setMessage("Address: 0x%02X\\nRegister: 0x%02X\\nResponse: %s\\n\\n%s\\n\\nA register response does not by itself prove chip identity.".format(test.address, test.register, hex, test.description))
                        .setPositiveButton("Close", null).show()
                    render()
                }
            } catch (e: Exception) {
                handler.post { busy = false; toast("Diagnostic failed: ${e.message}"); render() }
            }
        }
    }
    private fun executeSuite(suite: DeviceSuite) {
        val address = selected
        if (suite.steps.isEmpty()) {
            val report = DiagnosticReport(suite.id, suite.name, address, System.currentTimeMillis(),
                listOf(StepResult("Availability", TestStatus.INFO, emptyList(), suite.description)), emptyList())
            lastReport = report
            showReport(report)
            return
        }
        val d = driver ?: return
        if (busy || demo) return
        busy = true
        render()
        thread {
            val bus = object : DiagnosticBus {
                override fun probe(address: Int): Boolean = d.probe(address)
                override fun readRegister(address: Int, register: Int, length: Int): ByteArray =
                    d.readRegister(address, register, length)
                override fun write(address: Int, payload: ByteArray) = d.writeCommand(address, payload)
            }
            val report = DiagnosticEngine(bus).run(suite, address)
            handler.post {
                busy = false
                lastReport = report
                render()
                showReport(report)
            }
        }
    }
    private fun showReport(report: DiagnosticReport) {
        val summary = buildString {
            append(report.suiteName).append("\nAddress: 0x%02X".format(report.address))
            report.results.forEach { result ->
                append("\n\n").append(result.status).append(" · ").append(result.name)
                append("\n").append(result.message)
            }
            if (report.transcript.isNotEmpty()) {
                append("\n\nTransaction log:\n").append(report.transcript.joinToString("\n"))
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Diagnostic results")
            .setMessage(summary)
            .setNegativeButton("Close", null)
            .setNeutralButton("Copy JSON") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("I2C diagnostic report", report.toJson()))
                toast("JSON report copied")
            }
            .show()
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    override fun onDestroy() {
        unregisterReceiver(receiver)
        driver?.close()
        super.onDestroy()
    }
}
