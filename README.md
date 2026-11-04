# Nano Dash

[![ci](https://github.com/levi2m/nano-dash/actions/workflows/ci.yml/badge.svg)](https://github.com/levi2m/nano-dash/actions/workflows/ci.yml)

Ride logger for the DJI Osmo Nano on a motorcycle. It starts and stops the camera over Bluetooth with your ride, logs GPS and motion on the phone, and writes a `.fit` file that DJI Mimo can attach to the footage as speed, route and elevation overlays. No Mimo "sports training" session is needed, and the phone can stay locked in your pocket.

> [!WARNING]
> Unofficial. Not affiliated with, endorsed by or sponsored by DJI or Garmin. Not for safety-critical use. Never operate your phone while riding: start the ride before you move off.

## What it does

- Finds and pairs the camera, then starts and stops it with the ride. If the link drops it reconnects in the background and still stops the camera at the end. A camera that becomes ready after the ride started joins late.
- Sets the camera's clock to the phone's every time it connects, so the clips and the `.fit` share one time. This changes the camera's real date and time; the app warns if the phone's automatic time is off.
- Shows speed and distance in miles when the phone's language region is the US, UK or one of the few other countries that use them, and in kilometres elsewhere. "Speed unit" at the bottom of the screen switches it, and the choice is kept.
- Logs the ride with the screen off: GPS at 1 Hz, motion sensors at about 45 Hz, pressure.
- Writes the `.fit` with the same record fields as Mimo's own export, on real timestamps, plus developer fields (lean angle, lateral and longitudinal acceleration, camera recording flag) estimated from the GPS track.
- Controls several Nanos at once ("Choose cameras"). With cameras whose clocks differ, each gets its own `ride-<camera>.fit`.
- Opens a ride's route in a maps app that reads GPX (OsmAnd, Organic Maps and others): tap the route preview on the ride. The app draws no map itself, so it needs no network.
- Shows its version and build number at the bottom of the screen, with a "Check for updates" button that opens the releases page in your browser. The app makes no request itself; compare the build number with the latest tag (`v0.1.0-24` is build 24).
- Keeps everything on the phone. The app has no internet permission, and rides leave only when you tap Share.

Only Mimo draws the overlays, so Mimo is needed once, at the end, to import the `.fit` with the clip.

## Status

Works on a Samsung Galaxy S25 Ultra with one Osmo Nano: real rides imported in Mimo and lined up with their clips, including a short ride on build 32 with the phone locked in a pocket and the screen off, where the 360 ms clock offset, rounded seconds and phone-versus-GPS correction all lined up. Not yet proven on a real ride: two cameras at once, and rides longer than a few minutes with the screen off. Other DJI cameras are untested. See [ROADMAP.md](ROADMAP.md).

## How to use

1. Install the APK from the [latest release](https://github.com/levi2m/nano-dash/releases/latest), allow location, Bluetooth and notifications.
2. Turn the camera on with its wireless connection enabled, close Mimo on the phone, and tap Connect camera. The first time, approve the phone on the camera's screen.
3. Wait for the GPS row to show a fix, then tap Start ride and put the phone away.
4. Tap Stop ride (or Stop in the notification) when you arrive, and wait for "Saved".
5. Tap Share .fit on the ride, and in Mimo import the `.fit` together with the clip.

Rides live in the app's own folder on the phone (reachable over USB) and are deleted with the app, so share the ones you want to keep, or tap "Export all rides" for one zip of everything.

## Build

Needs JDK 21 and the Android SDK (platform 37). From `android/`:

```
./gradlew :core:test :app:assembleDebug
```

The `core` module (camera protocol, FIT writer, ride assembly) is plain Kotlin with no Android dependency, so its tests run on any JVM, with a fake Nano that reproduces the real camera's timings.

## Documents

- [docs/PROTOCOL.md](docs/PROTOCOL.md): what we confirmed about the Nano's Bluetooth control
- [docs/SYNC.md](docs/SYNC.md): how the `.fit` is lined up with the video, and how that was measured
- [docs/FIT.md](docs/FIT.md): what the `.fit` and a ride folder contain
- [PRIVACY.md](PRIVACY.md), [SECURITY.md](SECURITY.md), [CONTRIBUTING.md](CONTRIBUTING.md)

## Licence

Apache-2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). The camera protocol notes build on [Osmosis](https://github.com/KonradIT/osmosis) (MIT).
