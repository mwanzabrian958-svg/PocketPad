# PocketPad

PocketPad is an Android touch gamepad designed for gaming across PC, console, and cloud-play setups. It sends controller input; it does not run games or stream video/audio.

## Current implementation

This repository currently contains a runnable Android Compose app with authenticated Wi-Fi, ADB-reverse USB Companion, and Android Bluetooth HID input paths:

- Neon-dark Connect, Controller, and Settings tabs styled from the supplied UI mockups; the controller opens as one immersive landscape Xbox-style pad and scales its control layout to screen dimensions.
- General gaming-system connection picker with Auto, USB, Wi-Fi, and Bluetooth status cards. Wi-Fi, Companion-backed USB, and Android Bluetooth HID are implemented. Auto tries USB, Wi-Fi, then a paired Bluetooth host and attempts configured fallbacks after a drop.
- USB uses a framed TCP transport over an ADB reverse mapping. With USB debugging authorized, use the PC Companion's "Set up USB cable (ADB)" action; the app connects to the Companion through the phone's loopback interface.
- Bluetooth HID uses Android's `BluetoothHidDevice` profile, a gamepad report descriptor, bonded-host selection, and Nearby devices permissions. Some manufacturers omit or block the HID Device profile.
- "Help me choose" asks about Companion installation, cable availability, and shared Wi-Fi, then recommends Wi-Fi or USB ADB setup.
- In-app QR scanning through Google Play services Code Scanner, manual setup-link paste, IP/port/key inputs, remembered method, and live connection state.
- Same-network UDP broadcast discovery of running PC Companions; select a discovered host and paste its pairing link to provide the shared key.
- Authenticated Wi-Fi heartbeat monitoring with live round-trip latency and a disconnect report when Companion replies stop for two seconds.
- Optional last-host reconnect with the pairing key encrypted using Android Keystore-backed AES-GCM; disabling "Remember my choice" deletes the saved credentials.
- Xbox-letter or PlayStation-shape face-button labels can be selected. Every physical on-screen button can be remapped to a unique gamepad output, and mappings are stored with saved profiles.
- Multi-touch controller controls with two sticks, D-pad, face buttons, shoulder controls, analog triggers, Start/Select/Home.
- Hilt, DataStore settings, Room profiles for button labels/sensitivity/dead zone, pinned Gradle dependency catalog, release shrinker rules, and protocol tests.
- PC Companion with QR generation, local UDP receiver and discovery responder, optional Windows/Linux virtual controller output, HMAC authentication, replay ordering, watchdog neutral state, live input monitor, and user-initiated connection-log export.

Direct PlayStation/Xbox/Switch pairing and platform Remote Play integrations, a foreground connection service, and rumble are **not implemented**. The Wi-Fi Companion transport can run over USB tethering when Android exposes a reachable IP network; use the tethering setup guide and manual PC address if broadcast discovery does not work. Android cable state is monitored while the app is open, and the Companion restores its ADB reverse mapping after a USB replug. Bluetooth HID and USB ADB still require physical phone/PC validation. QR scanning uses Google Play services and may require its scanner module to be available; the setup link can always be pasted manually. USB ADB mode needs Android USB debugging and the desktop Companion; it is not raw USB HID. LAN discovery uses UDP broadcast rather than mDNS. The controller UI does not claim native compatibility or connect directly to consoles.

## Build the Android app

Install Android Studio with Android SDK platform 36, then:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

If the wrapper has not been generated yet, run `gradle wrapper --gradle-version 8.13` from Android Studio's Gradle installation, then use `.\gradlew.bat`. The default debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

Unit tests: `.\gradlew.bat testDebugUnitTest`. Instrumentation tests require an emulator or device.

## Build and run the PC Companion

See [`pc-companion/README.md`](pc-companion/README.md). It requires Python 3.10+. Install `pc-companion/requirements.txt`, then launch:

```powershell
python -m pocketpad_companion.app
```

For Wi-Fi, the PC and phone must be on the same reachable private network; allow inbound UDP port 26760 and discovery port 26761 on that network. Scan the QR code with PocketPad's in-app scanner or copy the setup link into PocketPad. For USB, connect the phone by cable with USB debugging enabled and authorized, then choose "Set up USB cable (ADB)" in the Companion and select USB in PocketPad. The USB TCP service binds only to the PC loopback interface and is carried over `adb reverse`; no USB firewall rule is needed. The pairing link contains a secret; keep it private.

Windows native gamepad output requires ViGEmBus plus `vgamepad`; Linux output needs `/dev/uinput` access and `evdev`. macOS native virtual controller support is not included. Missing drivers leave the Companion in explicit input-monitor-only mode.

## Visual references and accessibility

The supplied Connect, Controller, and Settings images informed the visual design: deep navy background, cyan/purple accents, glass-style cards, three-tab navigation, and a landscape-friendly controller. Generated-image artifacts (emulator chrome, duplicate face buttons, mixed Xbox/PlayStation labels, and debug text) are intentionally omitted. Touch controls have labels and the layout adapts to available width; a dedicated high-contrast theme and full localization are not implemented yet.

## Supported devices

Android API 26+ is the declared minimum. Wi-Fi input requires a reachable local PC. Bluetooth HID requires Android API 28+ and handset firmware that exposes the Bluetooth HID Device profile.

| Platform | Current support |
|---|---|
| Android 8+ phone/tablet | Compose UI; Wi-Fi and USB ADB Companion transports |
| Android 9+ handset exposing HID Device | Bluetooth HID gamepad; handset/OEM-dependent |
| Windows PC | Companion and ADB reverse; virtual Xbox output with ViGEmBus/vgamepad; Bluetooth host support varies |
| Linux PC | Companion and ADB reverse; virtual gamepad with evdev/uinput; Bluetooth host support varies |
| macOS PC | Companion/input monitor; no native virtual controller output; Bluetooth host support varies |
| USB tethering | Wi-Fi Companion protocol over the tethered network; manual host entry may be needed |

## Protocol, privacy, and manual testing

See [`docs/protocol.md`](docs/protocol.md) and [`docs/manual-test-checklist.md`](docs/manual-test-checklist.md). Android needs `INTERNET` permission for local UDP sockets. The app and companion do not use analytics or cloud services. The pairing key is included in the setup link; keep it private. When reconnection is enabled, Android stores the key encrypted with a Keystore-backed AES-GCM key.

## Current limitations

- ADB USB mode requires a single authorized physical USB phone, USB debugging, installed Android platform-tools, and the Companion to remain open. While PocketPad is open, it detects USB cable changes and retries a selected USB/Auto connection on replug; the Companion monitors and restores the ADB reverse mapping. Background reconnect is not provided by a foreground service.
- Bluetooth HID has report-format unit coverage but has not yet been verified with an actual phone HID Device profile and paired desktop host.
- The companion's generated key changes on each launch; pair again after restarting it.
- For best reachability use a private network with client isolation disabled.
- Xbox/PlayStation trademarks and logos are not used as app branding. PlayStation-style reporting and game-specific compatibility remain future work.
