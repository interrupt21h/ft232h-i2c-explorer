package io.github.interrupt21h.i2cexplorer

/**
 * Explicit, destructive SSD1306-compatible visual tests.
 * Page addressing works with common 128x32 and 128x64 SSD1306 modules.
 * No chip identity can be inferred from a successful transfer.
 */
object OledDiagnostics {
    enum class Pattern { CHECKERBOARD, VERTICAL_STRIPES, ALL_ON, ALL_OFF }

    fun image(width: Int, height: Int, pattern: Pattern): ByteArray {
        require(width == 128 && height in setOf(32, 64))
        return ByteArray(width * height / 8) { index ->
            val page = index / width
            val x = index % width
            when (pattern) {
                Pattern.CHECKERBOARD -> if (((x / 8) + page) % 2 == 0) 0xFF.toByte() else 0
                Pattern.VERTICAL_STRIPES -> if ((x / 8) % 2 == 0) 0xFF.toByte() else 0
                Pattern.ALL_ON -> 0xFF.toByte()
                Pattern.ALL_OFF -> 0
            }
        }
    }

    /** Blocking call; invoke on worker thread. Replaces framebuffer contents. */
    fun draw(bus: Ft232h, address: Int, height: Int, pattern: Pattern) {
        require(address == 0x3C || address == 0x3D)
        val pixels = image(128, height, pattern)
        // Control byte 0x00 means following bytes are commands.
        bus.writeCommand(address, byteArrayOf(0x00, 0x20, 0x02)) // page addressing
        for (page in 0 until height / 8) {
            bus.writeCommand(address, byteArrayOf(0x00, (0xB0 + page).toByte(), 0x00, 0x10))
            for (offset in 0 until 128 step 16) {
                val payload = byteArrayOf(0x40) + pixels.copyOfRange(page * 128 + offset, page * 128 + offset + 16)
                bus.writeCommand(address, payload)
            }
        }
        bus.writeCommand(address, byteArrayOf(0x00, 0xA4.toByte(), 0xA6.toByte(), 0xAF.toByte()))
    }
}
