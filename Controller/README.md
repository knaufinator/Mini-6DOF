# Mini-6DOF Controller Firmware

ESP-IDF v5.2 firmware for the ESP32 driving 6 hobby servos via LEDC PWM in a Stewart platform configuration.

## Project Structure

```
Controller/
├── main/
│   ├── main.cpp              # app_main entry point, FreeRTOS tasks, command API
│   ├── InverseKinematics.cpp # Stewart platform IK solver (shared with full-scale)
│   ├── AxisScaling.cpp       # Per-axis scaling + mapRawToPosition() (shared)
│   ├── BleTransport.cpp      # BLE GATT server (motion + accel characteristics)
│   ├── helpers.cpp           # mapfloat utility
│   └── CMakeLists.txt        # Component build config
├── include/
│   ├── InverseKinematics.h   # StewartConfig + PlatformDef structs, IK API
│   ├── AxisScaling.h         # AxisScaleConfig struct, workspace probing API
│   ├── helpers.h             # Pin definitions, servo parameters, timing constants
│   ├── version.h             # Firmware version + platform ID ("mini-6dof")
│   └── debug_uart.h          # Compile-time debug gating (DBG:1 / DBG:0)
├── CMakeLists.txt            # Top-level ESP-IDF project
└── sdkconfig.defaults        # ESP32 config (UART console, FreeRTOS 1kHz)
```

## Hardware

- **MCU**: ESP32 DevKitC (original ESP32 — not ESP32-S3)
- **Servos**: 6 × hobby servos (SG90/MG996R class)
- **PWM**: LEDC peripheral at 50 Hz, 16-bit resolution (~0.3 µs per tick)
- **Pulse range**: 800–2200 µs (center: 1500 µs)
- **Servo enable**: GPIO 27 — controls power relay/MOSFET for servo rail

### Pinout

| Servo | GPIO | LEDC Channel | Inverted |
|-------|------|-------------|----------|
| 0 | 15 | 0 | Yes |
| 1 | 14 | 1 | No |
| 2 | 4 | 2 | Yes |
| 3 | 32 | 3 | No |
| 4 | 33 | 4 | Yes |
| 5 | 5 | 5 | No |

| Function | GPIO |
|----------|------|
| Servo Enable | 27 |
| E-Stop | 22 |

Inverted servos are mounted mirrored — the firmware applies the sign flip automatically.

## Building

Requires [ESP-IDF v5.2](https://docs.espressif.com/projects/esp-idf/en/v5.2/esp32/get-started/index.html).

```bash
cd Controller
idf.py set-target esp32    # first time only
idf.py build
idf.py -p COM6 flash monitor
```

Replace `COM6` with your ESP32's COM port.

### Build Options

- **Debug output** (enabled by default in `main/CMakeLists.txt`):
  ```cmake
  target_compile_definitions(${COMPONENT_LIB} PRIVATE ENABLE_DEBUG_UART=1)
  ```
  Toggle at runtime with `DBG:1` / `DBG:0` serial commands.

- **Optimization**: `-O2 -ffast-math -fno-exceptions -fno-rtti` set in `main/CMakeLists.txt`.

## Boot Sequence

1. NVS flash init
2. Load Mini-6DOF geometry defaults (RD=15.75, PD=16, L1=7.25, L2=28.5, H=25.517 mm)
3. Probe IK workspace → compute axis scales with 90% safety margin
4. Configure LEDC PWM (50 Hz, 16-bit) on all 6 servo pins
5. Home all servos to center position (1500 µs)
6. 500 ms settle delay → enable servo power (GPIO 27 HIGH)
7. Start serial monitor task on Core 0
8. Print banner with firmware version, geometry, scales, fingerprint
9. Main loop idles — all motion is interrupt-driven from serial input

## Communication Protocol

### Binary Packet (preferred — 15 bytes)

| Offset | Size | Field |
|--------|------|-------|
| 0 | 1 | `0xAA` sync |
| 1 | 1 | `0x55` sync |
| 2 | 12 | 6 × `uint16_t` LE (surge, sway, heave, pitch, roll, yaw) |
| 14 | 1 | XOR checksum of bytes 2–13 |

Default input range: 0–4095 (12-bit). Adjustable via `BITS:N` command.

### Legacy CSV

`<v0>,<v1>,<v2>,<v3>,<v4>,<v5>X`

Comma-separated raw values terminated by `X`. Also used for command queries (e.g. `VERSION?X`).

### BLE (Android App)

The firmware includes a BLE GATT server for wireless control from the Android app. Device name: `Mini6DOF`.

| Characteristic | UUID | Size | Purpose |
|---------------|------|------|---------|
| Motion | `0xFF01` | 12 bytes | 6 × uint16 LE (same as serial binary) |
| Accel | `0xFF03` | 24 bytes | 6 × float32 LE (orientation + accel) |

**Accel characteristic pipeline** (phone-as-controller mode):

```
BLE [roll°, pitch°, yaw°, surge_ms2, sway_ms2, heave_ms2]
  → rotation: degrees × DEG_TO_RAD × gain → radians
  → translation: m/s² × gain → mm
  → IK → servo angles → pulse width → LEDC
```

Per-axis gains default to 1.0 for all 6 axes. Connection parameters request 7.5 ms interval for low latency.

### Processing Pipeline

```
Raw input (0–4095) → mapRawToPosition() → physical mm/rad → IK → servo angles → pulse width → LEDC
```

`mapRawToPosition()` maps the unsigned integer range to ± physical displacement using per-axis scales derived from geometry. Rotation axes are automatically converted to radians.

## Serial Commands

| Command | Description |
|---------|-------------|
| `VERSION?` | Firmware version, protocol version, platform ID, build date |
| `FINGERPRINT?` | MAC-based device ID + version + platform for handshake |
| `CONFIG?` | Dump geometry (RD, PD, L1, L2, H, θ_r, θ_p) + servo calibration |
| `CONFIG:key=value` | Set geometry param — auto-recomputes axis scales |
| `SCALE?` | Query current per-axis scales |
| `BITS?` | Query current input bit depth |
| `BITS:N` | Set input bit depth (8–16), updates max raw value |
| `SERVO:CENTER=c0,c1,c2,c3,c4,c5` | Set per-servo center calibration (µs) |
| `SERVO:PULSE=value` | Set pulse-per-radian multiplier |
| `ZERO` | Home all servos to center |
| `ESTOP:SOFT` | Emergency return to center |
| `DBG:1` / `DBG:0` | Enable/disable debug output |

## FreeRTOS Tasks

| Task | Core | Priority | Purpose |
|------|------|----------|---------|
| `SerialMonitor` | 0 | 5 | UART RX → binary/CSV parser → motion update |
| `app_main` | 0 | 1 | Init + idle watchdog loop |

Motion updates happen synchronously inside the serial monitor task — when a complete packet is received, it immediately runs the IK pipeline and updates all 6 servo PWM outputs.

## Shared Code

The IK and axis scaling modules are identical to the [full-scale controller](https://github.com/knaufinator/6DOF-Rotary-Stewart-Motion-Simulator/tree/main/Controller):

| File | Purpose |
|------|---------|
| `InverseKinematics.cpp/.h` | Closed-form Eisele IK solver, `StewartConfig`, `PlatformDef` |
| `AxisScaling.cpp/.h` | Geometry-based workspace probing, `mapRawToPosition()` |

These can be kept in sync by copying from the main project. The only platform-specific code is in `main.cpp` (LEDC servo output vs. MCPWM step/dir) and `helpers.h` (pin definitions).

## Differences from Full-Scale Controller

| Feature | Mini-6DOF | Full-Scale |
|---------|-----------|------------|
| **Output** | LEDC PWM (50 Hz hobby servo) | MCPWM step/dir (AC servo + gearbox) |
| **MCU** | ESP32 | ESP32-S3 |
| **Scale** | ~30 mm workspace | ~350 mm workspace |
| **Actuators** | Direct-drive hobby servos | 750W AC servos + 50:1 planetary |
| **BLE** | Yes — Android app control (phone-as-controller) | No |
| **Ethernet** | No | W5500 SPI (opt-in) |
| **WiFi** | No (can be enabled) | ESP-IDF WiFi STA |
| **Motion cueing** | Not included | Biquad washout filters |
| **E-stop** | Software only | Hardware GPIO + software |

## Troubleshooting

```bash
idf.py --version                    # verify ESP-IDF v5.2
idf.py fullclean && idf.py build    # clean rebuild after config changes
idf.py -p COM6 flash                # explicit port
idf.py monitor                      # serial monitor (Ctrl+] to exit)
```

**Servo jitter**: Check power supply — hobby servos under load can brown out if the supply is undersized. Use a dedicated 5V 8A supply, not USB power.

**No serial output**: Verify `CONFIG_ESP_CONSOLE_UART_DEFAULT=y` in `sdkconfig.defaults`. The original ESP32 uses UART0, not USB Serial JTAG.

## License

MIT — see [LICENSE](../LICENSE).
