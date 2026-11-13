package io.github.interrupt21h.i2cexplorer

import android.app.Activity
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.io.IOException
import kotlin.concurrent.thread

private const val ACTION_PERMISSION = "io.github.interrupt21h.i2cexplorer.USB_PERMISSION"

class MainActivity : Activity() {
    private lateinit var usb: UsbManager
    private lateinit var status: TextView
    private lateinit var output: TextView
    private var driver: Ft232h? = null
    private val main = Handler(Looper.getMainLooper())
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    val device = if (android.os.Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) connect(device)
                    else status.text = "USB permission denied"
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    driver?.close(); driver = null
                    status.text = "USB disconnected"
                }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usb = getSystemService(USB_SERVICE) as UsbManager
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        status = TextView(this).apply { text = "No FT232H connected"; textSize = 18f }
        output = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        val connectButton = Button(this).apply { text = "Connect FT232H"; setOnClickListener { discover() } }
        val scanButton = Button(this).apply { text = "Scan 0x08–0x77"; setOnClickListener { scan() } }
        val scroll = ScrollView(this).apply { addView(output) }
        root.addView(status); root.addView(connectButton); root.addView(scanButton)
        root.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        val filter = IntentFilter().apply { addAction(ACTION_PERMISSION); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        androidxRegisterReceiver(filter)
        discover()
    }
    private fun androidxRegisterReceiver(filter: IntentFilter) {
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    }
    private fun discover() {
        val device = usb.deviceList.values.firstOrNull { it.vendorId == 0x0403 && it.productId == 0x6014 }
        if (device == null) { status.text = "No FT232H (0403:6014) detected"; return }
        if (!usb.hasPermission(device)) {
            val intent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            usb.requestPermission(device, intent)
        } else connect(device)
    }
    private fun connect(device: UsbDevice) {
        status.text = "Connecting…"
        thread {
            try {
                driver?.close()
                val d = Ft232h(usb, device)
                driver = d
                main.post { status.text = "Connected: FT232H" }
            } catch (e: Exception) { main.post { status.text = "Connection failed: ${e.message}" } }
        }
    }
    private fun scan() {
        val d = driver ?: run { status.text = "Connect first"; return }
        status.text = "Scanning…"
        thread {
            val found = mutableListOf<String>()
            try {
                for (address in 0x08..0x77) {
                    if (d.probe(address)) found.add("0x%02X ACK".format(address))
                    main.post { output.text = found.joinToString("\n"); status.text = "Scanning 0x%02X…".format(address) }
                }
                main.post { status.text = "Scan complete: ${found.size} addresses" }
            } catch (e: Exception) { main.post { status.text = "Scan failed: ${e.message}" } }
        }
    }
    override fun onDestroy() { unregisterReceiver(receiver); driver?.close(); super.onDestroy() }
}

/** FT232H MPSSE GPIO-based open-drain I2C master. D0=SCL, D1=SDA output, D2=SDA input. */
class Ft232h(manager: UsbManager, device: UsbDevice) : AutoCloseable {
    private val conn = manager.openDevice(device) ?: throw IOException("Cannot open USB device")
    private val iface = device.getInterface(0)
    private val tx: UsbEndpoint
    private val rx: UsbEndpoint
    private var closed = false
    init {
        try {
            if (!conn.claimInterface(iface, true)) throw IOException("USB interface claim failed")
            tx = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
            rx = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
                .first { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_IN }
            control(0,0); control(9,1); control(11,0); control(11,0x0200)
            Thread.sleep(60)
            send(0x8a,0x97,0x8d,0x85,0x80,0,0,0x82,0,0)
            if (pins() and 0x05 != 0x05) throw IOException("SCL/SDA not high; check pull-ups")
        } catch (e: Exception) { close(); throw e }
    }
    private fun control(request:Int,value:Int) {
        if (conn.controlTransfer(0x40,request,value,1,null,0,2000)<0) throw IOException("Control transfer failed")
    }
    private fun send(vararg bytes:Int) {
        val b=ByteArray(bytes.size) { bytes[it].toByte() }
        if (conn.bulkTransfer(tx,b,b.size,2000)!=b.size) throw IOException("USB write failed")
    }
    private fun pins():Int {
        send(0x81,0x87)
        val b=ByteArray(rx.maxPacketSize)
        repeat(20) {
            val n=conn.bulkTransfer(rx,b,b.size,2000)
            if(n>2) return b[2].toInt() and 255
        }
        throw IOException("MPSSE read timed out")
    }
    private fun gpio(scl:Boolean,sda:Boolean) {
        val dir=(if(scl)0 else 1) or (if(sda)0 else 2)
        send(0x80,0,dir)
    }
    private fun start() { gpio(true,true); gpio(true,false); gpio(false,false) }
    private fun stop() { gpio(false,false); gpio(true,false); gpio(true,true) }
    private fun byte(value:Int):Boolean {
        for(bit in 7 downTo 0) {
            val high=value and (1 shl bit)!=0
            gpio(false,high); gpio(true,high); gpio(false,high)
        }
        gpio(false,true); gpio(true,true)
        val ack=pins() and 4 == 0
        gpio(false,true)
        return ack
    }
    private fun readByte(ack: Boolean): Int {
        var value = 0
        gpio(false, true)
        for (bit in 7 downTo 0) {
            gpio(true, true)
            if (pins() and 0x04 != 0) value = value or (1 shl bit)
            gpio(false, true)
        }
        gpio(false, !ack)
        gpio(true, !ack)
        gpio(false, true)
        return value
    }
    /** Atomic I2C transaction with repeated START; register address is one byte. */
    @Synchronized fun readRegister(address: Int, register: Int, count: Int = 1): ByteArray {
        require(address in 0x08..0x77 && register in 0..255 && count in 1..32)
        start()
        try {
            if (!byte(address shl 1)) throw IOException("Address write NACK")
            if (!byte(register)) throw IOException("Register NACK")
            start()
            if (!byte((address shl 1) or 1)) throw IOException("Address read NACK")
            return ByteArray(count) { readByte(it < count - 1).toByte() }
        } finally { stop() }
    }
    /** Raw I2C read, for command/response devices such as Si47xx. */
    @Synchronized fun readRaw(address: Int, count: Int): ByteArray {
        require(address in 0x08..0x77 && count in 1..32)
        start()
        try {
            if (!byte((address shl 1) or 1)) throw IOException("Address read NACK")
            return ByteArray(count) { readByte(it < count - 1).toByte() }
        } finally { stop() }
    }
    /** Command-only write, for explicitly confirmed diagnostic actions. */
    @Synchronized fun writeCommand(address: Int, command: ByteArray) {
        require(address in 0x08..0x77 && command.size in 1..32)
        start()
        try {
            if (!byte(address shl 1)) throw IOException("Address NACK")
            command.forEachIndexed { index, b ->
                if (!byte(b.toInt() and 255)) throw IOException("Data NACK at byte $index")
            }
        } finally { stop() }
    }
    @Synchronized fun probe(address:Int):Boolean {
        require(address in 0x08..0x77)
        start()
        return try { byte(address shl 1) } finally { stop() }
    }
    override fun close() {
        if(closed)return
        closed=true
        try { send(0x80,0,0,0x82,0,0) } catch (_:Exception) {}
        try { conn.releaseInterface(iface) } catch (_:Exception) {}
        conn.close()
    }
}
