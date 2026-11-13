# FT232H I²C Explorer

A native Android I²C bus explorer for FT232H USB-C breakout boards (including PAMEENCOS), with an offline demonstration interface.

## Interface

- Dark, touch-friendly UI with **Bus scan**, **Devices**, and **Guide** tabs.
- Offline demo mode with explicitly simulated ACKs, no hardware required.
- Interactive 7-bit address map (0x08–0x77).
- Address-based candidate catalog for 15 device families: Si4713, Si4703, SSD1306/SH1106, BME280/BMP280, SHT3x, ADS1115/ADS1015, MCP23017/MCP23008, PCF8574, DS3231/DS1307, MPU6050/MPU9250, 24xx EEPROM, INA219/INA226, PCA9685, VL53L0X, and AHT20/AHT21.
- Candidate matching does **not** claim device identity. Profiles describe possible devices at an address; no device-specific identification probes are implemented yet.

## Build

GitHub Actions builds the debug APK on pushes to main and version tags, and on pull requests and manual dispatch. Open the Actions tab and download the `ft232h-i2c-explorer-debug-apk` artifact from a successful run.

Local build: Android SDK 35, Java 17, Gradle 8.10.2, then `gradle :app:assembleDebug`.

## Wiring

- D0 → SCL
- D1 and D2 tied together → SDA
- GND → target GND
- External SDA and SCL pull-ups to an appropriate voltage

Check the actual breakout and target electrical characteristics before connecting. Do not assume that 5 V and 3.3 V pins are interchangeable.

## Limitations

The FT232H driver is experimental and has **not** been validated against physical hardware. GPIO-driven MPSSE I²C is slower than hardware-clocked I²C. Clock stretching and multi-master arbitration are not supported. Scanning may affect certain devices. Register reads/writes, full identification probes, export and logic tracing remain future work.

## Security and permissions

No root, network access or account required. USB permission is requested when connecting a device. Demo mode is available without USB hardware.
