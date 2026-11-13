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
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
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
        root = column().apply { setBackgroundColor(BG) }
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
        val scroll = ScrollView(this).apply { fillViewport = true }
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
        panel(host, "Device-specific diagnostics") { p ->
            val tests = diagnostics.filter { it.address == selected }
            if (tests.isEmpty()) p.addView(label("No safe register-read recipe for this address yet. OLEDs, Si47xx radios, and other command-based devices require protocol-specific handling.", 13f, MUTED))
            tests.forEach { test ->
                p.addView(label(test.name, 14f, FG, true)); gap(p, 4)
                p.addView(label(test.description, 12f, MUTED)); gap(p, 8)
                p.addView(action("Read register 0x%02X".format(test.register), !busy && !demo && driver != null) { runDiagnostic(test) }); gap(p, 12)
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
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    override fun onDestroy() {
        unregisterReceiver(receiver)
        driver?.close()
        super.onDestroy()
    }
}
