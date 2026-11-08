# Security

## Reporting

Please report security problems privately through GitHub's "Report a vulnerability" on this repository, not as a public issue.

## Known and accepted

- The Osmo Nano's Bluetooth control link is unencrypted by DJI's design and the pairing identifier travels in the clear. A nearby device that overhears it could control the camera. Nano Dash cannot fix this; it is the camera's protocol.
- With "Quick start from the watch" on, the phone listens for the watch on an encrypted Bluetooth serial channel. Only the one paired device you chose as your watch is served; anything else is disconnected at once. The watch can only ask for the ride state and to start or stop a ride. Anyone who controls that paired watch can start or stop your rides.
- Release signing secrets (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`) are repository-level secrets until the owner moves them into the `release` environment restricted to `main` (steps in docs/RELEASING.md). Until then a workflow on any branch of the repository could read them. The release job that uses them has a read-only token; a separate job without secrets publishes.
- Gradle dependency verification is on: every library and plugin Gradle downloads must match a SHA-256 in `android/gradle/verification-metadata.xml`, so a changed artifact fails the build. The hashes were recorded from what the repositories served (trust on first use), and signatures are not checked. Dependencies are pinned by version in `android/gradle/libs.versions.toml`, the Gradle wrapper is validated in CI, and Dependabot proposes updates.
- Debug builds are signed with a debug key and are for testing only.
- Rides are kept in the app's external files folder, not in private internal storage, so they can be reached over USB when troubleshooting. On Android 10 and older, another app with storage permission can read them and also write to them (change or add ride files); on Android 11 and later it cannot. Accepted for now, and it may move to private storage later.

## Known limits of the camera link

- The camera picker lists raw Bluetooth addresses, so two similar cameras are told apart by address only.
- The scan filter (what counts as an Osmo Nano) is based on advertised data that any nearby device can imitate, so a look-alike can appear in the picker.
- The controller id the app uses is sent to whichever device you choose, and replies are matched on the command only, so a device that answers like a camera is trusted as one.

## Known limits of the watch link

- The Bluetooth service id is public (it is in the open source). Another app on the phone could open the same service and answer the watch as if it were Nano Dash. This has not been tried on a real device yet. The watch would then show a false ride state, and it sends only start, stop and status requests, so no ride data, location or file is exposed this way.
- No app identity is exchanged: the phone trusts the paired device you chose, not the app on it, and the watch trusts the paired phone it connected to.

## Audits

Three audit rounds have been run on the code, with fixes after each. The last security audit had no confirmed findings and two points that need validation: the signing secrets being repository-level (above) and the service id impersonation on the watch link (above).

## Hardening in the app

No internet permission in the phone or the watch app (CI checks the merged manifests and the built APKs on every build); the service is not exported; the Start button in the ready notification needs the phone unlocked on Android 12 and later; releases are built by a job with a read-only token and published by a separate job with no secrets, from actions pinned by hash; ride files are read with a size cap; the file provider only exposes the `rides/` folder and a cache folder for temporary share copies; pending intents are immutable; backup and device transfer are off.
