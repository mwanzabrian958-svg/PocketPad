# PocketPad manual test checklist

This checklist requires a physical Android phone and a PC. Automated build checks do not replace testing ADB authorization, device HID profile availability, and actual cable behavior on hardware.

## Verified hardware results (2026-10-02)

- TECNO CD6 (Android 10): USB ADB reached the Companion and delivered non-neutral button input; the app reported approximately 12 ms round-trip latency.
- TECNO CD6 Bluetooth HID registration succeeded. Windows paired with the phone, and Windows enumerated both a Bluetooth HID device and an HID-compliant game controller.
- Bluetooth button/axis reports have not yet been verified in Windows' game controller panel. The phone's USB debugging connection dropped after pairing, before the app could be returned to its Bluetooth host selection screen.
- With user approval, the signed ViGEmBus 1.22.0 driver and pinned `vgamepad` 0.1.0 package were installed. The driver is running; creating the virtual Xbox controller and sending/resetting a button report succeeded. End-to-end input in a real game remains to be checked.
- The PC Companion is running with Wi-Fi/discovery listeners and a USB loopback listener. On the Public Wi-Fi profile, inbound UDP rules are limited to Companion ports 26760/26761, the Python executable, and the local subnet.
- PocketPad offers **Scan Companion QR code** in both its Wi-Fi and USB connection setup panels.

## Current Wi-Fi path

- [ ] Install the debug APK on Android 8 or newer.
- [ ] Create a Python 3 environment in `pc-companion`, install `requirements.txt`, and launch `python -m pocketpad_companion.app`.
- [ ] Allow UDP port 26760 on the private network only.
- [ ] Scan the Companion QR code from the PocketPad connection picker, or paste its `pocketpad://connect?...` link.
- [ ] Use "Find automatically" on the same LAN and confirm the Companion appears; select it and paste its pairing link for the key.
- [ ] Confirm the app connects and reports an initial round-trip time.
- [ ] Touch both sticks and several buttons simultaneously; confirm the live Companion test view updates.
- [ ] Disconnect Wi-Fi or close the Companion and confirm the UI reports a lost connection.
- [ ] Leave input held, then stop sending; confirm the Companion neutralizes controls within 200 ms.
- [ ] Alter one byte of a datagram and confirm it is ignored.
- [ ] Test on a phone hotspot and on a private router.
- [ ] Enable USB debugging, authorize the phone, click **Set up USB cable (ADB)** in the Companion, paste its pairing link in PocketPad USB settings, and connect.
- [ ] While using USB, verify controller inputs and measured round-trip latency; unplug the cable, confirm the failure is surfaced, then reconnect while PocketPad and Companion remain open and verify the reverse tunnel and controller link recover.
- [ ] In Wi-Fi mode, enable USB tethering on the phone, connect the PC to its USB network, use Companion discovery or enter the PC's tethered IP, and verify input.
- [ ] Configure USB, Wi-Fi, and a paired Bluetooth host, choose Auto, confirm USB is preferred, then drop the USB link and confirm it switches to Wi-Fi within the reconnect window.
- [ ] In Auto mode, close the Companion and confirm it switches to the selected paired Bluetooth host when supported.
- [x] Grant Nearby devices permission, register the gamepad service, make the phone discoverable, and pair from Windows Bluetooth settings.
- [ ] Select the paired PC in PocketPad Bluetooth and verify buttons, sticks, and triggers in the OS game controller panel.
- [ ] In Settings, switch between Xbox and PlayStation face-button labels; confirm TalkBack names and button positions remain correct.
- [ ] Remap several buttons, verify output labels swap without duplicates, then save/switch profiles and confirm mappings, labels, sensitivity, and dead zone restore.
- [ ] In Settings, set **Low-power input** on, connect over Wi-Fi, and confirm input still registers while the Companion's reported input rate drops to roughly a quarter of the normal rate.
- [ ] With **Low-power input** on, press and release a button quickly and confirm the release is applied immediately rather than waiting for the next transmit window.
- [ ] At several sensitivity values, push each stick fully to every edge and confirm the game still reaches full deflection, and that small motion inside the dead zone stays neutral.
- [ ] Export the Companion log and confirm it contains recent connection/input events without pairing keys.

## Not available or hardware-dependent

- [ ] Confirm app-background reconnect after cable return (foreground service is not implemented).
- [ ] Verify rumble, multi-phone assignment, direct console integrations, and real-game compatibility.
