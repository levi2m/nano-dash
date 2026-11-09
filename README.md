# Nano Dash

[![ci](https://github.com/levi2m/nano-dash/actions/workflows/ci.yml/badge.svg)](https://github.com/levi2m/nano-dash/actions/workflows/ci.yml)

Ride logger for the DJI Osmo Nano on a motorcycle. It starts and stops the camera over Bluetooth with your ride, logs GPS and motion on the phone, and writes a `.fit` file that DJI Mimo can attach to the footage as speed, route and elevation overlays. No Mimo "sports training" session is needed, and the phone can stay locked in your pocket.

> [!WARNING]
> Unofficial. Not affiliated with, endorsed by or sponsored by DJI or Garmin. Not for safety-critical use. Never operate your phone while riding: start the ride before you move off.

## What it does

- Finds and pairs the camera, then starts and stops it with the ride. If the link drops it reconnects in the background and still stops the camera at the end. A camera that becomes ready after the ride started joins late.
- Sets the camera's clock to the phone's every time it connects, so the clips and the `.fit` share one time. This changes the camera's real date and time; the app warns if the phone's automatic time is off.
- Shows speed and distance in miles when the phone's language region is the US, UK or one of the few other countries that use them, and in kilometres elsewhere. The Settings sheet (top right) switches it, and the choice is kept.
- Logs the ride with the screen off: GPS at 1 Hz, motion sensors at about 45 Hz, pressure.
- Writes the `.fit` with the same record fields as Mimo's own export, on real timestamps, plus developer fields (lean angle, lateral and longitudinal acceleration, camera recording flag) estimated from the GPS track.
- Controls several Nanos at once ("Choose cameras"). With cameras whose clocks differ, each gets its own `ride-<camera>.fit`.
- Wear OS remote (optional, off by default): start a ride with one tap and stop it with a press and hold from a Galaxy Watch (or another Wear OS 4 watch), with the phone staying in the pocket. Switch on "Quick start from the watch" in the phone app (Settings, top right), choose your watch from the paired devices, and install the watch APK from the release over ADB with developer mode on the watch (the phone app has a "Watch app" row with the steps and shows "Connected before" or "Not seen yet"; Wear OS cannot install an app from the phone, and an installer would need the internet permission this app does not have). Start from the notification needs the phone unlocked on Android 12 and later; Start from the watch does not. The phone keeps one quiet notification while it is on. The link is a Bluetooth serial channel over the watch's existing pairing: no network and no Google libraries. See [docs/WEAR_OS.md](docs/WEAR_OS.md).
- Opens a ride's route in a maps app that reads GPX (OsmAnd, Organic Maps and others): tap the route preview on the ride. The app draws no map itself, so it needs no network.
- Works with screen readers such as TalkBack: section titles are marked as headings, each status row is read as one phrase (for example "GPS: Location is on"), the route preview and the live speed have spoken descriptions, and every control is at least 48 dp. It follows the phone's light or dark theme and large font sizes. The main screen has been checked with TalkBack on one phone. The Settings sheet and the watch screens were written to the same rules and installed, but have not been confirmed with TalkBack yet; reports of anything that reads badly are welcome.
- Shows its version and build number at the bottom of the screen, with a credit line ("Built by levi2m and his AI agents", where levi2m opens the author's GitHub page). Settings has a "Check for updates" row that opens the releases page in your browser (hidden on F-Droid installs, which F-Droid updates). The app makes no request itself; the build number is the number after the dash in a release tag, for example `v0.1.0-58` is build 58.
- Keeps everything on the phone. The app has no internet permission, and rides leave only when you tap Share.

Only Mimo draws the overlays, so Mimo is needed once, at the end, to import the `.fit` with the clip.

## Status

Works on a Samsung Galaxy S25 Ultra (Android 17) with one Osmo Nano: real rides imported in Mimo and lined up with their clips, including a short ride on build 32 with the phone locked in a pocket and the screen off, where the 360 ms clock offset, rounded seconds and phone-versus-GPS correction all lined up. The Wear OS remote works end to end on a Galaxy Watch6 Classic paired with that phone. Not yet proven on a real ride: GPS fixes outdoors on a ride started from the watch, two cameras at once, rides longer than a few minutes with the screen off, and other watches. Other DJI cameras are untested. See [ROADMAP.md](ROADMAP.md).

## How to use

1. Install `nano-dash-<tag>.apk` (Android 8 or newer) from the [latest release](https://github.com/levi2m/nano-dash/releases/latest), allow location, Bluetooth and notifications. Each release lists the APK's SHA-256 and the signing certificate's; check a download with `sha256sum -c <file>.sha256`. Updates install over the old version.
2. Turn the camera on with its wireless connection enabled, close Mimo on the phone, and tap Connect camera. The first time, approve the phone on the camera's screen.
3. Wait for the GPS row to show a fix, then tap Start ride and put the phone away.
4. Tap Stop ride (or Stop in the notification) when you arrive, and wait for "Saved".
5. Tap Share .fit on the ride, and in Mimo import the `.fit` together with the clip.

Rides live in the app's own folder on the phone (reachable over USB) and are deleted with the app, so share the ones you want to keep, or tap "Export all rides" for one zip of everything.

## Build

Needs JDK 21 and the Android SDK (platform 37). From `android/`:

```
./gradlew :core:test :app:assembleDebug :wear:assembleDebug
```

The `core` module (camera protocol, FIT writer, ride assembly) is plain Kotlin with no Android dependency, so its tests run on any JVM, with a fake Nano that reproduces the real camera's timings.

## Documents

- [docs/PROTOCOL.md](docs/PROTOCOL.md): what we confirmed about the Nano's Bluetooth control
- [docs/SYNC.md](docs/SYNC.md): how the `.fit` is lined up with the video, and how that was measured
- [docs/FIT.md](docs/FIT.md): what the `.fit` and a ride folder contain
- [docs/WEAR_OS.md](docs/WEAR_OS.md) and [docs/WEAR_UI.md](docs/WEAR_UI.md): the watch remote and its screens
- [docs/GARMIN.md](docs/GARMIN.md): plan for a Garmin watch remote (idea only, not built)
- [docs/RELEASING.md](docs/RELEASING.md) and [docs/FDROID.md](docs/FDROID.md): how releases are built, and the reproducible F-Droid build
- [ROADMAP.md](ROADMAP.md): what is proven, next and left out
- [PRIVACY.md](PRIVACY.md), [SECURITY.md](SECURITY.md), [CONTRIBUTING.md](CONTRIBUTING.md)

## Licence

Apache-2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). The camera protocol notes build on [Osmosis](https://github.com/KonradIT/osmosis) (MIT).
