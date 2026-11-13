# FT232H I²C Explorer

A native Android I²C bus explorer for FT232H USB-C breakout boards (including PAMEENCOS), with an offline demonstration interface.

## Download the latest Android APK

**[⬇ Download the latest successful APK build](https://github.com/interrupt21h/ft232h-i2c-explorer/actions/workflows/android.yml?query=branch%3Amain+is%3Asuccess)**

Open the top successful workflow run, scroll to **Artifacts**, and download **ft232h-i2c-explorer-debug-apk**. Extract the ZIP and install the APK on Android. GitHub login and repository access are required because this repository is private. Artifacts expire after 30 days; use the newest successful run.

[All build runs](https://github.com/interrupt21h/ft232h-i2c-explorer/actions/workflows/android.yml) · [Latest main branch source](https://github.com/interrupt21h/ft232h-i2c-explorer/tree/main)

## Si4713 live diagnostics

Select **Devices → 0x63 → Run detailed Si4713 tests**. The confirmation-gated suite records a raw status byte (CTS/ERR flags), sends the non-transmitting `GET_REV (0x10)` command only when CTS is ready, polls for completion, reads its 9-byte firmware response, and exports a verbose JSON transcript. It does **not** issue POWER_UP, tune, or RF transmission commands. An unpowered device may NACK or remain unready; this is reported rather than automatically activating the transmitter. A live ACK does not prove chip identity. Six JVM tests cover ready, unready, transport failure, command error, short response and invalid address. Physical hardware validation remains outstanding.

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

The FT232H driver is experimental and has **not** been validated against physical hardware. GPIO-driven MPSSE I²C is slower than hardware-clocked I²C. Clock stretching and multi-master arbitration are not supported. Scanning may affect certain devices. Limited read-only register diagnostics and an explicitly confirmed OLED display toggle are implemented. Generic register editor, full identification probes, export and logic tracing remain future work.

## Security and permissions

No root, network access or account required. USB permission is requested when connecting a device. Demo mode is available without USB hardware.

## Display compatibility

| Board | Protocol | Scan address | Notes |
| --- | --- | --- | --- |
| HiLetgo 0.96-inch 128×64 SSD1306 OLED | I²C | Usually 0x3C or 0x3D | Requires 4-pin GND/VCC/SCL/SDA wiring |
| DSD TECH 0.91-inch 128×32 OLED | I²C | Usually 0x3C or 0x3D | Same address does not reveal resolution |
| LAFVIN 2.13-inch 250×122 e-paper HAT | SPI | Not applicable | DIN/CLK/CS/DC/RST/BUSY; SPI driver is not implemented |

The address scan sends an I²C address probe and checks ACK. It cannot reliably identify the exact OLED controller, panel dimensions, or distinguish two devices sharing an address. The e-paper module requires a future SPI mode; it cannot be discovered by I²C scanning.

**Hardware validation pending:** No physical FT232H or target modules have been connected to this project in CI. USB behavior, SDA input sampling, clock speed and real device discovery are not verified.

## Device-specific tests

Open **Devices**, select an address, then **Device-specific diagnostics**.

- BME280/BMP280: read chip ID (0xD0) at 0x76 or 0x77.
- MPU6050: read WHO_AM_I (0x75) at 0x68 or 0x69.
- INA219: read configuration (0x00) at 0x40.
- ADS1115: read conversion register (0x00) at 0x48.
- MCP23017: read IODIRA (0x00) at 0x20.
- DS3231: read status (0x0F) at 0x68.
- SSD1306-compatible OLED at 0x3C or 0x3D: **opt-in write test**, sends display OFF then ON, with confirmation and visual verification.

Tests run only with connected hardware. These are address-specific example recipes, not automatic chip identification. An address match alone never proves device identity. The OLED test modifies device state and should only be used on a known OLED. Si47xx command/response diagnostics and SPI e-paper tests are not implemented.

**Hardware caveat:** FT232H read transactions and USB status handling have not yet been tested on real hardware; diagnostic success is not guaranteed.

## Reusable diagnostic framework

The device inspector now includes declarative read-only suites from `DiagnosticCatalog`. Each suite specifies supported addresses, register reads, expected byte values (when available), and explanatory notes. `DiagnosticEngine` runs these through a `DiagnosticBus` abstraction and produces PASS / FAIL / INFO / ERROR step results, a transaction transcript, and a versioned JSON report. Use **Copy JSON** in the results dialog to export to the clipboard.

Initial suites: BME280/BMP280, MPU6050, DS3231, ADS1115/ADS1015, INA219, MCP23017, VL53L0X, and availability notes for SSD1306, Si4713, and 24xx EEPROM. Suites with no safe generic read remain informational; they do not send arbitrary commands. The existing OLED visual test remains separate and requires confirmation.

`DiagnosticEngineTest` uses a fake transport to exercise positive/negative IDs, errors, unsupported tests and invalid addresses without USB hardware. The GitHub Actions workflow runs JVM unit tests before building the APK.

**Known limitations:** hardware read timing, actual ACK responses and GPIO-level behavior are unverified. A PASS means the observed value matches a known expectation, not a cryptographic or definitive device identification. The transcript records requested reads and responses, not electrical bus waveforms.
