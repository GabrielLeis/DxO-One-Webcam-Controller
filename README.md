# dxo-one-webcam-server

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

## CRITICAL: Camera Hardware Modification (`autoexec.ash`)

Out of the box, the DxO ONE acts strictly as a Mass Storage device or an Apple iAP2 accessory. To allow this Java controller to intercept the video stream via USB, you **must** switch the camera into developer mode. 

This is done by overriding the Ambarella RTOS boot sequence using an internal shell script.

### Installation Steps:
1. Extract the microSD card from your DxO ONE and plug it into your computer.
2. Copy the `autoexec.ash` file (provided in the `sd_card_root/` folder of this repository) directly into the **root** of the microSD card.
3. Insert the microSD card back into the camera.
4. Turn on the camera (Slide the lens cover open).

**What does this script do?**
* `t dxo iap2_toggle off`: Disables the Apple Lightning port routing, freeing up the internal data bus.
* `t dxo micro_usb_connected_toggle on`: Forces the micro-USB port to act as an active data peripheral rather than just a charging/mass-storage port.

> **IMPORTANT WARNING:** While this script is active on the SD card, **you will not be able to connect the camera to an iPhone** (the Lightning port is disabled), and it will no longer mount as a standard USB drive on your PC. It effectively turns the camera into a dedicated development board. 
> 
> **To revert to normal factory behavior:** Simply delete or rename the `autoexec.ash` file from the microSD card and reboot the camera.

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

## Executable Scripts

If you want to excecute the server without typing anything on the terminal you can create an automation script for both Windows and MacOS.
You must place this file inside the same folder where you downloaded the Fat JAR. Both of them (for Windows and MacOS) are in the **executable_scripts/** folder.

For example, you can create a folder in the Desktop, name it 'DXO-Server'. Place the .jar file inside and place the script for your Operating System. Then just double click it and use it as normal.

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
