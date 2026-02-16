# PCBv1 — Original Mini-6DOF Board Firmware

> **This firmware is for the original (v1) Mini-6DOF PCB.** If you have a newer board revision, see [`Controller/`](../Controller/README.md) instead.

ESP-IDF v5.2 firmware for the ESP32 driving 6 hobby servos via LEDC PWM in a Stewart platform configuration. This is the production firmware that was running on the original PCB design with the specific GPIO pinout and servo rail power control documented below.

## PCBv1 Hardware

- **MCU**: ESP32 DevKitC (original ESP32-D0WDQ6 — **not** ESP32-S3)
- **Servos**: 6 × hobby servos (SG90/MG996R class), 50 Hz PWM
- **Power**: 5V 8A dedicated supply for servo rail (**not** USB power)
- **Servo Enable**: GPIO 27 — controls power relay/MOSFET for servo rail
- **E-Stop**: GPIO 22

### PCBv1 Pinout

| Servo | GPIO | LEDC Channel | Inverted | Notes |
|-------|------|-------------|----------|-------|
| 0 | 15 | 0 | Yes | Mirrored mount |
| 1 | 14 | 1 | No | |
| 2 | 4 | 2 | Yes | Mirrored mount |
| 3 | 32 | 3 | No | |
| 4 | 33 | 4 | Yes | Mirrored mount |
| 5 | 5 | 5 | No | |

| Function | GPIO |
|----------|------|
| Servo Enable | 27 |
| E-Stop | 22 |

### Platform Geometry (mm)

| Parameter | Value | Description |
|-----------|-------|-------------|
| RD | 15.75 | Base radius |
| PD | 16.00 | Platform radius |
| L1 | 7.25 | Servo horn length |
| L2 | 28.50 | Connecting rod length |
| H | 25.517 | Neutral platform height |
| θ_r | 10° | Base rotation angle |
| θ_p | 30° | Platform rotation angle |

## Optimizations for PCBv1

This firmware includes several optimizations tuned for the PCBv1 circuit and hobby servo characteristics:

### PWM Precision
- **16-bit LEDC resolution** at 50 Hz — each tick ≈ 0.305 µs, giving ~4587 discrete steps across the 800–2200 µs servo range
- **Integer math `usToDuty()`** — no float division in the hot path, uses `uint64_t` multiply for <0.05% error
- Atomic batch update: all 6 duties set first, then all 6 updated simultaneously to prevent phase skew

### Input Bit Depth
- **Default: 14-bit** (0–16383) — 4× finer than the standard 12-bit, giving smoother motion on the small-geometry platform where each bit of input precision maps to ~0.002 mm of physical displacement
- Configurable 8–16 bit via `BITS:N` command (persisted to NVS)

### Motion Loop
- **200 Hz servo update rate** (5 ms cycle) — decoupled from telemetry rate
- BLE accel data processed every 5 ms regardless of telemetry output throttling
- Telemetry runs at its own configurable rate (default 50 Hz, max 100 Hz)

### Slew-Rate Limiting
- **2 mm/cycle at 200 Hz** = 400 mm/s max velocity — fast enough for responsive BLE phone control, smooth enough to prevent servo jitter on the compact geometry
- Per-axis independent limiting prevents coupled-axis oscillation

### BLE Transport
- **7.5 ms connection interval** — lowest standard BLE interval for sub-10ms latency
- Dual characteristics: motion (12-byte uint16) and accel (24-byte float32)
- Accel pipeline: rotation deg→rad, translation m/s²→mm with per-axis gain

### Known PCBv1 Limitations
- **No Ethernet** — original ESP32 doesn't have a W5500 SPI connection on this board
- **50 Hz servo PWM only** — standard hobby servo frequency; some digital servos support higher but the PCBv1 circuit was designed for this
- **Software E-stop only** — GPIO 22 is wired but no hardware power cutoff circuit
- **USB power insufficient** — servos under load will brown out; always use dedicated 5V 8A supply

## Building & Flashing

Requires [ESP-IDF v5.2](https://docs.espressif.com/projects/esp-idf/en/v5.2/esp32/get-started/index.html).

```bash
cd PCBv1
idf.py set-target esp32    # first time only
idf.py build
idf.py -p COM6 flash monitor
```

### Build Options

- **Debug output** (enabled by default):
  ```cmake
  target_compile_definitions(${COMPONENT_LIB} PRIVATE ENABLE_DEBUG_UART=1)
  ```
  Toggle at runtime: `DBG:1` / `DBG:0`

- **Optimization**: `-O2 -ffast-math -fno-exceptions -fno-rtti`

## Communication

### Serial (USB CDC) — 115200 baud

**Binary (15 bytes, up to 1000 Hz):**

| Offset | Size | Field |
|--------|------|-------|
| 0 | 1 | `0xAA` sync |
| 1 | 1 | `0x55` sync |
| 2 | 12 | 6 × `uint16_t` LE (surge, sway, heave, pitch, roll, yaw) |
| 14 | 1 | XOR checksum of bytes 2–13 |

**Legacy CSV:** `<v0>,<v1>,<v2>,<v3>,<v4>,<v5>X`

### BLE (Android App)

Device name: `Mini6DOF`. Two GATT characteristics:

| Characteristic | UUID | Payload | Purpose |
|---------------|------|---------|---------|
| Motion | `0xFF01` | 12 bytes: 6 × uint16 LE | Manual / IMU percent mode |
| Accel | `0xFF03` | 24 bytes: 6 × float32 LE | Phone orientation + accelerometer |

**Accel format:** `[roll°, pitch°, yaw°, surge_ms², sway_ms², heave_ms²]`

## Serial Commands

| Command | Response | Description |
|---------|----------|-------------|
| `VERSION?` | Version, protocol, platform, date | Firmware identity |
| `FINGERPRINT?` | MAC + version + platform | Device handshake |
| `CONFIG?` | Geometry + servo calibration | Full config dump |
| `CONFIG:key=value` | Confirmation | Set geometry (auto-recomputes scales) |
| `SCALE?` | Per-axis scales | Query computed scales |
| `BITS?` / `BITS:N` | Bit depth info | Query/set input bit depth (8–16) |
| `SERVO:CENTER=c0,...,c5` | New centers | Calibrate servo centers (µs) |
| `SERVO:PULSE=value` | New value | Set pulse-per-radian multiplier |
| `TELRATE:N` | Confirmation | Set telemetry rate 1–100 Hz |
| `MCA?` / `MCA:preset` | Config | Query/set motion cueing preset |
| `ACCEL?` | Gain + map + mode | Query accel input config |
| `ACCEL:GAIN=s,sw,h,r,p,y` | New gains | Set per-axis accel gains |
| `ACCEL:MAP=s,sw,h,r,p,y` | New mapping | Set axis mapping (1-based, neg=invert) |
| `ZERO` | `ZERO:OK` | Home all servos to center |
| `ESTOP:SOFT` | Confirmation | Emergency return to center |
| `DBG:1` / `DBG:0` | Confirmation | Toggle debug output |

## FreeRTOS Architecture

| Task | Core | Priority | Rate | Purpose |
|------|------|----------|------|---------|
| `SerialMonitor` | 0 | 5 | Event-driven | UART RX → binary/CSV parser → servo update |
| `app_main` loop | 0 | 1 | 200 Hz | BLE accel processing + watchdog + telemetry |

Serial binary packets trigger immediate IK + servo update in the serial task. BLE accel data is flag-polled in the 200 Hz main loop.

## Fingerprint & Desktop App Integration

The firmware reports `platform=mini-6dof` in both `VERSION?` and `FINGERPRINT?` responses. The [desktop HIL app](https://github.com/knaufinator/6DOF-Rotary-Stewart-Motion-Simulator/tree/main/app) uses this to:
- Auto-detect the platform variant on connect
- Load appropriate geometry defaults
- Apply correct axis scaling for the mini workspace

## Shared Code

Identical to the [full-scale controller](https://github.com/knaufinator/6DOF-Rotary-Stewart-Motion-Simulator):

| File | Purpose |
|------|---------|
| `InverseKinematics.cpp/.h` | Closed-form Eisele IK solver |
| `AxisScaling.cpp/.h` | Geometry-based workspace probing |
| `MotionCueing.cpp/.h` | Classical washout filters |
| `BleTransport.cpp/.h` | BLE GATT server |

## License

MIT — see [LICENSE](../LICENSE).
