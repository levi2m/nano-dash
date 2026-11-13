# Garmin: what works today, and the remote plan

Status: **low priority, nothing Garmin-specific is built.** The maintainer has no Garmin watch, so every step below waits for a volunteer who has one. Facts about Garmin were checked on 13 November 2026 against Garmin's documentation and its developer forum (sources at the end); where only the forum says it, this file says so.

## 1. The app as it is, for a Garmin rider

Nano Dash runs on the phone and needs no watch:

1. Pair the Osmo Nano once in the app.
2. Before moving off, tap Start. The camera starts recording, the phone sets the camera's clock, and GPS and motion are logged with the screen off and the phone in a pocket.
3. Tap Stop at the end (or from the notification). The camera stops and the app writes a `.fit` file.
4. In DJI Mimo, import the clip together with the `.fit` to get speed, route, elevation and lean overlays that line up with the video.

What a Garmin watch adds today is unknown and untested: see section 3.

## 2. Can the Wear OS companion run on a Garmin?

No. Garmin watches run Garmin's own system and install only Connect IQ apps (written in Monkey C with Garmin's SDK). The Wear OS APK cannot be installed, and its link cannot be ported: Connect IQ has no Bluetooth Classic serial channel, which is what the Wear OS remote uses.

What does carry over is the phone side: the armed service, `WatchProtocol` in `core`, the start refusals (`gps-off`, `no-permission`, `busy`), the "Quick start from the watch" setting, and the rules "tap to start, hold to stop" and "Phone not reachable".

## 3. Levels, cheapest first

| Level | What the rider gets | New code | Status |
|---|---|---|---|
| 0 | The full phone app, no remote | none | works today |
| 1 | Stop, and maybe Start, from the phone notification mirrored by Garmin Connect | none | **untested on a Garmin** |
| 2 | A Connect IQ remote over Bluetooth LE (route A) | large | not started; needs a volunteer's watch |
| 3 | A remote through Garmin's Connect IQ Mobile SDK (route B) | medium, plus a proprietary library | **dropped** (section 5) |

### Level 1: notifications (step 0, no code)

Garmin Connect mirrors phone notifications to the watch. Garmin's manuals say the actions offered on the watch depend on the notification type and the phone's OS; custom action buttons are not documented (one third-party project describes Android actions mapping onto watch slots). So it is plausible, not proven.

With "Quick start from the watch" on, the phone keeps a "Nano Dash is ready" notification with **Start ride**, and a ride shows a notification with **Stop**. Known limit: on Android 12 and later, **Start** from a notification asks for the phone to be unlocked (a deliberate guard, so a stranger with your locked phone cannot start a ride), so Start from the wrist would wait on the phone. **Stop** has no such guard and is the button most likely to work.

If a volunteer reports that Start appears but waits for an unlock, the decision is whether to drop the unlock while Quick start is on. That is a security trade-off to make on purpose, not a default.

### Level 2: route A, Bluetooth LE

- **What Connect IQ offers:** the `Toybox.BluetoothLowEnergy` module, **central role only** (the watch connects to other devices; it cannot be connected to), from API level 3.1. Garmin's docs say this; the forum adds that support differs between models even at 3.1, so each target watch is checked in the SDK's device list.
- **So the phone is the peripheral:** it advertises a small service and runs a GATT server in the armed foreground service; the watch app scans and connects. Forum reports of watches failing to see a phone app's advertising make this the first thing the spike checks.
- **Unknown:** whether the phone can hold this link while Garmin Connect keeps its own Bluetooth link to the same watch.
- **Authentication has to be ours:** the Wear OS link borrows the phone's Bluetooth pairing; this one cannot. A code shown on the phone and confirmed on the watch, then keyed messages with replay protection, designed and written here before any code. A stray BLE client must not start or stop a ride.
- **Battery:** advertise slowly while armed.
- **Installing:** a `.prg` file copied to `GARMIN/APPS`. On current watches this still works, per the forum, but newer models connect over MTP (as a media device, not a USB drive), hide the file after copying, and accept only a `.prg` built for that exact model. Easier than ADB on Wear OS, but one build per model. The Connect IQ Store is the later option.
- **CI:** building `.prg` files needs the Connect IQ SDK; whether its agreement allows us to redistribute the built files is unchecked.

## 4. Plan

0. **Notification test (no code).** A Garmin owner turns on Quick start, starts and stops a ride from the watch notification, and reports what appeared and what worked. Asked for in the compatibility issue template.
1. **Decide from the answers.** If Stop works from the watch for most people, a Connect IQ app may never be needed. If Start is wanted, decide the unlock question in section 3.
2. **Only if a Connect IQ remote is still wanted, and a volunteer with an API 3.1+ watch will test:** spike a Connect IQ app that finds the phone over BLE, sends `HELLO 1` and `STATUS`, and shows the reply. Nothing else is built until this works on a real watch.
3. Authentication design, written here.
4. Phone side: a BLE transport in the armed service, reusing `WatchProtocol`; tests in `core` for any new logic.
5. Watch screens: Start, Stop (hold), state, "Phone not reachable".
6. CI and docs, if the SDK agreement allows it.

## 5. Decisions

- **Route B (Connect IQ Mobile SDK) is dropped.** The library is on Maven Central but under Garmin's proprietary agreement (personal, revocable, not sublicensable), and it needs Garmin Connect on the phone. It would force a second, GitHub-only APK with different contents, while the project now ships one APK everywhere: the F-Droid build is reproducible and published with our own signature. Revisit only if route A is shown impossible on the watches people own.
- **Out of scope:** the watch's own GPS, heart rate or recording; older Garmin models without the BLE API; any Garmin SDK material in this repository.

## 6. What we need from riders

A Garmin owner who would help: open a "Works on my phone or camera" issue, or answer on the Garmin issue, with the watch model and firmware (watch settings, System, About), the phone and Android version, what the Nano Dash notifications looked like on the watch (step 0), and whether they would sideload a test app.

## Sources (checked 13 November 2026)

- [Toybox.BluetoothLowEnergy](https://developer.garmin.com/connect-iq/api-docs/Toybox/BluetoothLowEnergy.html) (Garmin API docs)
- [BLE is central-only](https://forums.garmin.com/developer/connect-iq/i/bug-reports/feature-request-support-ble-gap-broadcaster-short-burst-advertising-in-toybox-bluetoothlowenergy), [needs API 3.1](https://forums.garmin.com/developer/connect-iq/f/discussion/377887/most-affordable-device-that-supports-api-3-1-0-for-ble-development), [not every 3.1 device has it](https://forums.garmin.com/developer/connect-iq/f/discussion/226313/bluetooth-smart-device-missing-toybox-bluetoothlowenergy), [scanning a phone peripheral](https://forums.garmin.com/developer/connect-iq/f/discussion/396729/unable-to-scan-ble-peripheral-advertised-by-nrf-connect-on-garmin-watch-app) (forum)
- [Viewing notifications](https://www8.garmin.com/manuals/webhelp/vivoactive3music/EN-US/GUID-42E25863-50ED-4009-AC6E-EAE698BD226E.html) (Garmin manual); [Android actions on watch slots](https://codeberg.org/OpenVitals/mobile-app/pulls/233) (third party)
- [Sideloading on current watches](https://forums.garmin.com/developer/connect-iq/f/discussion/419051/sideloading-on-a-watch), [where .prg files go](https://forums.garmin.com/developer/connect-iq/f/discussion/391547/where-do-the-app-prg-files-go-now-on-the-devices/1860915) (forum)
- [Connect IQ Android SDK](https://github.com/garmin/connectiq-android-sdk), [Maven Central artifact](https://central.sonatype.com/artifact/com.garmin.connectiq/ciq-companion-app-sdk/), [SDK agreement](https://developer.garmin.com/downloads/connect-iq/sdks/agreement.html)
