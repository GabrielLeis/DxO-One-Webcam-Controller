# DxO-One-Webcam-Controller

> A user-space driver and MJPEG server built in Java 21 to use the Ambarella-based DxO ONE action camera as a live webcam via USB.

---

## Overview

By default, the DxO ONE camera does not support standard USB Video Class (UVC) protocols, making it unrecognizable by standard OS webcam drivers. This project bypasses that limitation by directly interfacing with the camera's internal Real-Time Operating System (RTOS) using a **reverse-engineered JSON-RPC protocol** over `libusb`.

It extracts raw MJPEG byte streams directly from the hardware, assembles them dynamically in memory, and serves them via a local TCP socket for seamless integration with broadcasting software like OBS Studio.

---

## Architecture

To maintain strict determinism and avoid UI or network blocking, the application implements a highly efficient **Producer-Consumer multithreading pattern**:

```
┌─────────────────────┐      ┌───────────────────┐      ┌──────────────────────┐
│  Producer           │      │  Blocking Queue   │      │  Consumer            │
│  (USB Polling)      │ ───► │  (Frame Buffer)   │ ───► │  (TCP MJPEG Server)  │
│  libusb / JNI       │      │  Drop-if-lagging  │      │  localhost:8080      │
└─────────────────────┘      └───────────────────┘      └──────────────────────┘
```

1. **Producer (USB Polling):** Interfaces with the C-native `libusb` library via JNI. Sends the proprietary handshake (`0xA3 0xBA...`), claims the USB interfaces, and continuously polls the `IN` endpoint (`0x82`). Scans incoming raw byte chunks for standard JPEG headers (`FF D8 FF`) and trailers (`FF D9`) using zero-copy `ByteBuffer` techniques to prevent memory leaks.

2. **Blocking Queue:** A thread-safe, capacity-restricted concurrent queue. Buffers assembled frames and intelligently drops outdated frames if the network consumer is lagging — ensuring **zero latency accumulation** (real-time video).

3. **Consumer (TCP MJPEG Server):** A lightweight socket server listening on `localhost:8080`. Wraps consumed JPEG byte arrays into an HTTP `multipart/x-mixed-replace` stream, enabling any connected client to render a continuous video feed.

---

## Hardware Quirks & State Machine

Interfacing directly with the Ambarella RTOS reveals specific power-saving behaviors at the physical layer that this driver actively manages:

* **The Lens Cover Switch:** The camera's internal firmware will not instantiate the video USB pipes (Endpoints `0x82` and `0x01`) unless the physical lens cover is slid open. 
* **USB PHY Disconnection:** If the camera is plugged in with the cover closed, it defaults to a charging state. To save battery, the firmware actively drops the USB data lines (`D+`/`D-`) while continuing to draw 5V power from `VBUS`. To the host OS, this mimics a physical cord disconnection, throwing a `LIBUSB_ERROR_NO_DEVICE` or `Access Denied` at the kernel level.
* **Resilient Polling:** Instead of crashing or leaking memory when the hardware disappears, this Java application implements a hardware-aware polling loop. It intercepts the physical disconnect, safely releases the native C memory pointers (`LibUsb.close`), and passively waits for the hardware interrupt (the user opening the cover) to re-claim the interfaces and resume the video stream automatically.

---

## Prerequisites

| Requirement | Details |
|---|---|
| **Java** | 21 or higher |
| **Maven** | 3.x |
| **macOS / Linux** | Works natively by detaching the kernel driver |
| **Apple Silicon (M1/M2/M3/M4)** | Must run an `x86_64` (Intel) JDK via Rosetta 2 — the `usb4java` native binaries are compiled for `darwin-x86_64` |
| **Windows** | Requires replacing the camera's USB driver with `WinUSB` or `libusb-win32` using [Zadig](https://zadig.akeo.ie/) |

---

## Build & Run

**1. Clone the repository:**
```bash
git clone https://github.com/GabrielLeis/DxO-One-Webcam-Controller.git
cd DxO-One-Webcam-Controller
```

**2. Build with Maven:**
```bash
mvn clean compile
```

**3. Run the server:**
```bash
mvn exec:java -Dexec.mainClass="org.gabrielleis.DxoOneWebcamServer"
```

**Expected output:**
```
Starting USB-to-TCP bridge for DxO ONE...
MJPEG server listening on http://localhost:8080/video
Camera initialized. Starting frame capture...
```

---

## Acknowledgments

This project stands on the shoulders of the open-source community. A massive thank you to the following researchers and developers whose prior reverse-engineering efforts made this Java implementation possible:

- [**jsyang/dxo1control**](https://github.com/jsyang/dxo1control)

- [**yeongrokgim/dxo-one-firmware-study**](https://github.com/yeongrokgim/dxo-one-firmware-study)

- [**rickdeck/DxO-One**](https://github.com/rickdeck/DxO-One)

---

## Contributing

Pull requests are welcome! For major changes, please open an issue first to discuss what you would like to change.

---

## License

[MIT](LICENSE)
