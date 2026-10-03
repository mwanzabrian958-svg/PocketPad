# PocketPad PC Companion

The Companion receives authenticated PocketPad input over Wi-Fi UDP or a local TCP service forwarded over USB ADB reverse, and echoes frames for latency measurement. A small Tk window shows a pairing QR code, a copyable setup link, USB setup, exported diagnostics, and live input state.

## Run from source

Use Python 3.10 or newer. From this directory:

```powershell
python -m venv .venv
.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python -m pocketpad_companion.app
```

Linux/macOS activation uses `source .venv/bin/activate`. For Wi-Fi, allow inbound UDP 26760 and 26761 on a **private/trusted network** only. The app binds those UDP services to local adapters and the pairing key is shown in its QR code; do not share that code with untrusted devices.

The Companion does not contact external services. It creates a new random pairing key each launch. Scan the QR with PocketPad's in-app scanner or paste its `pocketpad://connect?...` link into the Android app.

### USB cable (ADB reverse)

1. Enable Developer options and USB debugging on the Android phone, connect it to this PC, and accept the phone's debugging authorization prompt.
2. Keep this Companion running and click **Set up USB cable (ADB)**. The Companion locates Android platform-tools, requires exactly one authorized physical USB phone, and runs `adb reverse tcp:26762 tcp:26762`. Once initialized, it monitors that device and restores the reverse mapping after a cable replug.
3. In PocketPad, choose **USB Cable**, paste the pairing link, and connect. The Companion's TCP listener is bound to `127.0.0.1:26762` and is not exposed to the LAN.
4. Keep PocketPad open for cable-state detection and reconnect. Closing the Companion removes its reverse mapping. USB debugging and the Companion are required; PocketPad does not emulate raw USB HID. Reconnection while PocketPad is in the background is not supported.

### USB tethering

Enable USB tethering in Android network settings and connect the PC to the phone's tethered network. In PocketPad, use the Wi-Fi method with the Companion setup link and the PC's tether-network IP address. Broadcast discovery may not work on every tether interface, so manual address entry is supported.

## Virtual controller drivers

- **Windows:** Install ViGEmBus from its official project distribution before installing `vgamepad`. The ViGEmBus project is no longer actively maintained; use at your own risk. The Companion falls back to an input monitor if its driver/package is absent.
- **Linux:** Install `evdev` and configure `/dev/uinput` permissions for the logged-in user. The Companion reports a clear input-monitor-only state if unavailable.
- **macOS:** Native virtual-controller output is not included. The app can use Android Bluetooth HID on phones that expose the HID Device profile; validate pairing and input against the target macOS release.

Controller output uses Xbox-style button ordering. This program does not install drivers or request administrator privileges. Bluetooth HID works without the PC Companion, but support depends on the phone's Bluetooth HID Device profile.

## Packaging

Install the pinned dependencies, then run PyInstaller on each target operating system:

```sh
pyinstaller --noconfirm --windowed --name PocketPad-Companion -m pocketpad_companion.app
```

Build on the target operating system for its native executable. Driver installers and credentials are not bundled.
