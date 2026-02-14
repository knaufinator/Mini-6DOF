# Mini-6DOF Stewart Platform

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform: ESP32](https://img.shields.io/badge/Platform-ESP32-blue.svg)](https://www.espressif.com/en/products/socs/esp32)
[![Firmware: ESP-IDF v5.2](https://img.shields.io/badge/Firmware-ESP--IDF%20v5.2-red.svg)](https://docs.espressif.com/projects/esp-idf/en/v5.2/)

> Desktop-scale 6-DOF Stewart platform powered by hobby servos and an ESP32, running production firmware derived from the [full-scale 6DOF Rotary Stewart Motion Simulator](https://github.com/knaufinator/6DOF-Rotary-Stewart-Motion-Simulator).

<img src="Images/platform1.jpg" width="480">

## Overview

The Mini-6DOF is a compact Stewart platform designed for development, testing, and education. It shares the same inverse kinematics engine, serial command API, and binary communication protocol as the full-scale platform — making it a drop-in test bed for firmware and desktop app development.

The `Controller/` firmware is a native ESP-IDF v5.2 project (phoenix branch rewrite) that replaces the original Arduino sketch with a production-grade implementation featuring proper IK workspace probing, binary protocol support, and full compatibility with the [desktop HIL control app](https://github.com/knaufinator/6DOF-Rotary-Stewart-Motion-Simulator/tree/main/app).

### What's New (Phoenix Branch)

- **ESP-IDF native firmware** — replaced Arduino `.ino` with proper ESP-IDF v5.2 project
- **Shared IK engine** — same `InverseKinematics.cpp` and `AxisScaling.cpp` as the full-scale controller
- **Automatic workspace probing** — axis scales computed from geometry, no manual tuning
- **Binary protocol** — 15-byte framed packets (0xAA/0x55 + 6×uint16 LE + XOR checksum) at up to 1000 Hz
- **Unified command API** — `VERSION?`, `FINGERPRINT?`, `CONFIG?`, `SCALE?`, `BITS?`, `ZERO`, `ESTOP:SOFT`
- **Desktop app compatible** — works as an HIL entity in the native C++/ImGui control app
- **Runtime configurable** — geometry, servo calibration, bit depth, axis scales — all adjustable over serial

## Repository Layout

| Directory | Contents |
|-----------|----------|
| `Controller/` | **ESP-IDF v5.2 firmware** (production) — see [Controller/README.md](Controller/README.md) |
| `MiniServoController/` | Original Arduino sketch (legacy reference, not maintained) |
| `Android/` | Android BLE controller app (legacy reference, not maintained) |
| `3D Parts/` | STL files for 3D-printed servo mounts and seat rails |
| `PCB/` | Custom PCB gerbers and BOM |
| `Images/` | Project photos |

## Quick Start

```bash
# 1. Clone
git clone https://github.com/knaufinator/Mini-6DOF.git
cd Mini-6DOF/Controller

# 2. Build & flash (requires ESP-IDF v5.2)
idf.py set-target esp32
idf.py build
idf.py -p COM6 flash monitor
```

See [Controller/README.md](Controller/README.md) for full build instructions, pinout, and serial commands.

## Hardware

- **MCU**: ESP32 DevKitC (original ESP32, not S3)
- **Servos**: 6 × hobby servos (50 Hz PWM, 800–2200 µs pulse range)
- **Power**: 5V 8A supply for servo rail
- **Servo Enable**: GPIO 27 controls power relay/MOSFET for servo rail
- **Frame**: 3D-printed mounts + Panhard-style linkage rods

### Servo Pinout

| Servo | GPIO | Inverted |
|-------|------|----------|
| 0 | 15 | Yes |
| 1 | 14 | No |
| 2 | 4 | Yes |
| 3 | 32 | No |
| 4 | 33 | Yes |
| 5 | 5 | No |

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

## Communication

### Serial (USB CDC) — 115200 baud, 8N1

**Binary (preferred — 15 bytes, up to 1000 Hz):**

| Offset | Size | Field |
|--------|------|-------|
| 0 | 1 | `0xAA` sync |
| 1 | 1 | `0x55` sync |
| 2 | 12 | 6 × `uint16_t` LE — surge, sway, heave, pitch, roll, yaw (0–4095 default) |
| 14 | 1 | XOR checksum of bytes 2–13 |

**Legacy CSV:** `<v0>,<v1>,<v2>,<v3>,<v4>,<v5>X`

Both protocols are handled simultaneously. The binary packet state machine takes priority; ASCII bytes fall through to the CSV/command parser.

### SimTools Setup

| Setting | Value |
|---------|-------|
| Interface Type | Serial |
| COM Port | (your ESP32 port, e.g. COM6) |
| Baud Rate | 115200 |
| Output - Bit Range | 12 |
| Output - Type | Decimal |
| Axis Mapping | `x, y, z, Ry, Rx, Rz` |
| Output Format | `<Axis1a>,<Axis2a>,<Axis3a>,<Axis4a>,<Axis5a>,<Axis6a>X` |

### Desktop App (HIL Mode)

The Mini-6DOF works as an HIL entity in the [native desktop app](https://github.com/knaufinator/6DOF-Rotary-Stewart-Motion-Simulator/tree/main/app). The app auto-detects the platform via `FINGERPRINT?` (reports `platform=mini-6dof`) and loads appropriate geometry defaults.

## Serial Commands

All commands use `X` or newline as terminator.

| Command | Response | Description |
|---------|----------|-------------|
| `VERSION?` | `VERSION:1.0.0,proto=1,platform=mini-6dof,...` | Firmware version |
| `FINGERPRINT?` | `FINGERPRINT:<MAC>,fw=1.0.0,proto=1,platform=mini-6dof` | Device identity |
| `CONFIG?` | Geometry + servo calibration | Full platform config dump |
| `CONFIG:key=value` | `CONFIG:OK key=value` | Set geometry param (RD, PD, L1, L2, height, theta_r, theta_p) |
| `SCALE?` | `SCALE:s0,s1,s2,s3,s4,s5` | Current axis scales |
| `BITS?` | `BITS:12,max_raw=4095` | Current input bit depth |
| `BITS:N` | `BITS:N,max_raw=...` | Set bit depth (8–16) |
| `SERVO:CENTER=c0,...,c5` | Echo new centers | Calibrate servo center positions (µs) |
| `SERVO:PULSE=value` | Echo new value | Set pulse-per-radian multiplier |
| `ZERO` | `ZERO:OK` | Home all servos to center |
| `ESTOP:SOFT` | Confirmation | Emergency return to center |
| `DBG:1` / `DBG:0` | Confirmation | Enable/disable debug output |

## Relationship to Full-Scale Platform

This project is the desktop-scale companion to the [6DOF Rotary Stewart Motion Simulator](https://github.com/knaufinator/6DOF-Rotary-Stewart-Motion-Simulator). The firmware shares:

- **Inverse kinematics** — same `InverseKinematics.cpp/.h` (Eisele closed-form solution)
- **Axis scaling** — same `AxisScaling.cpp/.h` (geometry-based workspace probing)
- **Serial protocol** — same binary packet format and command API
- **Platform identification** — `FINGERPRINT?` returns `platform=mini-6dof` so the desktop app can distinguish it from the full-scale `platform=stewart-6dof`

The key differences are output stage (LEDC PWM for hobby servos vs. MCPWM step/dir for AC servos) and scale (millimeters vs. hundreds of millimeters).

## License

MIT — see [LICENSE](LICENSE).
