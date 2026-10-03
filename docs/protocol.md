# PocketPad local controller protocol

Protocol version 1 is a full-state frame format. The Android sender and PC receiver use little-endian fields and HMAC-SHA256. The desktop Companion listens on UDP port `26760` by default and echoes each accepted datagram to measure round-trip latency. USB transfers these 79-byte frames over TCP forwarded to loopback port `26762` by `adb reverse`; the TCP receiver reads fixed-size authenticated frames and echoes accepted frames.

## Datagram layout

| Offset | Size | Field |
|---:|---:|---|
| 0 | 4 | ASCII magic `PPD1` |
| 4 | 1 | Protocol version (`1`) |
| 5 | 16 | Session ID: first 16 bytes of SHA-256(pairing key) |
| 21 | 4 | Unsigned sequence number |
| 25 | 8 | Unix timestamp in milliseconds |
| 33 | 4 | Buttons bit mask |
| 37 | 2 | Left X, signed -32767 to 32767 |
| 39 | 2 | Left Y, signed -32767 to 32767 |
| 41 | 2 | Right X, signed -32767 to 32767 |
| 43 | 2 | Right Y, signed -32767 to 32767 |
| 45 | 1 | Left trigger, unsigned 0 to 255 |
| 46 | 1 | Right trigger, unsigned 0 to 255 |
| 47 | 32 | HMAC-SHA256 over bytes 0 through 46 |

Total datagram size is 79 bytes. The 32-byte random pairing key is represented as 64 hexadecimal characters in the Companion setup link. The key is a local-network pairing secret; only share it with the intended phone.

Button bits: D-pad up, down, left, right (0–3); A, B, X, Y (4–7); LB, RB (8–9); Start, Back (10–11); Guide (12); L3, R3 (13–14).

## Reliability and security

- Send the complete controller state on changes and at 125 Hz while connected; never depend on deltas.
- The Companion authenticates before applying input, checks the derived session ID and a five-second timestamp window, and rejects duplicate, old, or reordered sequence numbers.
- A 200 ms receiver watchdog writes neutral input after packets stop.
- The companion echoes only authenticated datagrams to support latency measurement.
- Version numbers are independent of app releases. Unknown versions and malformed packets are rejected.

## Current scope

Version 1 supports authenticated Wi-Fi UDP (including reachable USB-tether networks) and Companion-backed USB ADB TCP transport, plus an Android Bluetooth HID gamepad report path. Auto tries USB, Wi-Fi, and then a selected/paired Bluetooth host; if an active route drops, it attempts the remaining configured routes. Bluetooth HID is handset/OEM dependent and awaits physical phone/host validation. Android monitors cable state while the activity is foregrounded; the Companion restores its ADB reverse mapping after a replug and a selected USB/Auto connection is retried. A foreground service for background reconnection, mDNS, and Companion-to-phone rumble are not implemented. Android QR scanning uses Google Play services Code Scanner; the setup URI can also be pasted into the app's connection sheet.

Bluetooth HID reports use report ID 1 with a 16-bit button mask, four signed 16-bit axes in little-endian order, and two unsigned 8-bit triggers. Visual face-button labels do not alter emitted button bit positions.
