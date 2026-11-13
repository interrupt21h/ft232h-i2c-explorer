# FT232H I²C Explorer

Experimental native Android I²C scanner for FT232H USB-C breakouts, including the PAMEENCOS board. Android USB Host API; no root required.

## Wiring

D0 = SCL, D1 and D2 tied together = SDA, GND = GND. Use suitable external pull-ups and compatible target voltage. Do not connect 5 V logic to a 3.3 V-only target.

## Build

GitHub Actions builds a debug APK on pushes and pull requests. Download the artifact named `ft232h-i2c-explorer-debug-apk` from the Actions run.

Locally: Android SDK 35, Java 17, Gradle 8.10.2, then `gradle :app:assembleDebug`.

## Limitations

This is an experimental driver, not hardware validated. The GPIO-driven MPSSE approach is slower than native clocked I²C. No clock stretching or multi-master arbitration. Address ACK does not establish device identity. Scanning may affect certain devices; use on a controlled bus.
