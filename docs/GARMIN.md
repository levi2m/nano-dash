# Garmin remote: plan

Status: **idea, not started, low priority.** Nothing here is built or tested. The Garmin facts below come from the maintainer's general knowledge of Connect IQ and have **not** been checked against Garmin's current documentation or a real watch. Step 1 of the plan is to check them.

## 1. Goal

The same remote as on Wear OS ([WEAR_OS.md](WEAR_OS.md), [WEAR_UI.md](WEAR_UI.md)): Start with one tap, Stop with a press and hold, the ride state on the wrist, while the phone stays in the pocket and keeps the GPS, sensors and camera link. The watch never talks to the camera and never records the ride.

**Scope: newer Garmin watches only.** Older models are out, because the link below needs a Connect IQ version and a Bluetooth Low Energy API that older watches lack. The exact cut-off is decided in step 1, from the Connect IQ device list and from what a real watch does, not guessed here.

## 2. What carries over and what does not

| Part | Reused? |
|---|---|
| `WatchProtocol` in `core` (text lines, `STATE ...`) | Yes, as the message format |
| Armed phone service, start refusals (`gps-off`, `no-permission`, `busy`), "Quick start" setting | Yes |
| UI rules: tap to start, hold to stop, "Phone not reachable" | Yes, rewritten |
| The `:wear` Kotlin app and its Bluetooth Classic (RFCOMM) link | No |

A Garmin app is a **Connect IQ** app written in **Monkey C** with Garmin's SDK. It is a separate toolchain and a separate build, in a new `garmin/` folder, and it shares no code with `:wear`.

## 3. The link: both options are planned

**Decision: we go with both options**, not one or the other. They serve different people, and neither is certain to work on every newer watch until the spike says so.

- **Option A (Bluetooth LE) is the main route.** It keeps the phone app free of proprietary code, so it can ship in the F-Droid build and in the GitHub build.
- **Option B (Connect IQ Mobile SDK) is the second route**, built as a **GitHub-only build flavour** (never in the F-Droid build), for watches or users where A does not work. It also covers the case where A turns out to be impossible on the target watches.
- **Both routes carry the same `WatchProtocol` lines** and talk to the same armed service, so the ride logic is written once. Each route is only a transport behind a small interface in the phone app, and the Connect IQ watch app has one transport per route behind its own small interface.
- **Order:** the spike tests A first (step 2). B is built after A's spike result, either as a fallback or as the second supported route.
- **Cost of choosing both:** two transports to build, test and document, and a second build flavour with its own release APK and `NOTICE` text. That is why the whole item stays low priority.

Connect IQ apps cannot open a Bluetooth Classic serial channel, so the Wear OS link does not port.

| | Option | Needs | Cost | Verdict |
|---|---|---|---|---|
| A | **Bluetooth LE.** The phone advertises a small GATT service; the watch app connects to it as a BLE client | Nothing proprietary in the phone app. A Connect IQ version with the BLE API on the watch | A GATT server and advertising on the phone, and **our own pairing and authentication** (the Wear OS link borrows the existing Bluetooth pairing) | **Main route.** Spike first |
| B | **Connect IQ Mobile SDK.** The phone app talks to the watch app through the Garmin Connect app on the phone | Garmin's Android library (proprietary licence) and the Garmin Connect app installed and running | Less code, but the dependency breaks the F-Droid-clean rule | **Second route**, only as a GitHub-only build flavour so the main app stays free of it |

Either way, the "no internet permission" promise must hold in the phone app. Option B has to be checked for this before it is accepted.

### Option A: what has to be designed

- **Authentication:** a secret shown on the phone and entered or confirmed on the watch, then a signed or keyed message with replay protection. A stray BLE client must not be able to start or stop a ride. This needs a design pass before any code, as `WEAR_OS.md` section 6 said for its own "Stage 2".
- **Only the user's watch is accepted**, as on Wear OS.
- **Android background limits:** the phone must advertise while the app is closed. The armed foreground service (started while the app is on screen) is the planned answer, as on Wear OS.
- **Battery:** advertising continuously costs power. Advertise slowly when armed.

## 4. Installing the watch app

Connect IQ apps are `.prg` files. Expected routes: copy the file over USB to the watch's `GARMIN/APPS` folder (sideload), or later list it in the Connect IQ Store. USB sideloading is easier than the ADB steps on Wear OS. To be confirmed in step 1, including whether newer watches still allow it.

## 5. Plan

1. **Check the facts (no code).** Ask users for their watch model (see section 7). Read Garmin's current Connect IQ documentation: which devices have the BLE API, which Connect IQ version, whether sideloading works on them, and the licence of the Mobile SDK. Fix the "newer models" cut-off from that.
2. **Spike.** The smallest Connect IQ app that finds the phone over BLE, sends `HELLO 1` and `STATUS`, and shows the reply. Run on a real watch borrowed from or tested by a user. Nothing else is built until this works. If BLE fails on the target watches, option B becomes the only route for them.
3. **Authentication design**, written into this document before code.
4. **Phone side:** a transport interface in the armed service, then the BLE GATT server (option A). The protocol in `core` is reused, with tests for the new parts.
4b. **Option B:** the Mobile SDK transport in the GitHub-only flavour, plus the matching transport in the watch app. Before it ships, check the SDK licence, that no internet permission is added, and update `NOTICE` and the release checklist.
5. **Watch screens:** Start, Stop (hold), state, "Phone not reachable".
6. **CI:** build the `.prg` with the Connect IQ SDK and attach it to releases, if the SDK licence allows redistribution of the build. Docs and roadmap follow.

## 6. What stays out

- The watch's own GPS, heart rate or any recording on the watch (same reasoning as `WEAR_OS.md` option D).
- Older Garmin models.
- Any Garmin SDK material in the main app or in the repository tree. `NOTICE` says there is none today. If option B is ever used, the library comes in only in its own build flavour, and `NOTICE` and the release checklist change with it.

## 7. What we need from users

Anyone with a Garmin watch who would test: open a "Works on my phone or camera" issue, or reply on the Garmin issue, with:

- The watch model and its firmware version (watch settings, System, About).
- The phone model and Android version.
- Whether they are happy to sideload a test app over USB.

## 8. Open questions

1. Which watch models, and does BLE-client work from Connect IQ on them?
2. Is USB sideloading still possible on those models?
3. Can the Connect IQ SDK build be run in CI, and may the resulting `.prg` be redistributed?
4. Is the Mobile SDK acceptable at all in a GitHub-only flavour, given the licence? (If not, option B is dropped and the plan is A only.)
