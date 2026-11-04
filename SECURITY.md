# Security

## Reporting

Please report security problems privately through GitHub's "Report a vulnerability" on this repository, not as a public issue.

## Known and accepted

- The Osmo Nano's Bluetooth control link is unencrypted by DJI's design and the pairing identifier travels in the clear. A nearby device that overhears it could control the camera. Nano Dash cannot fix this; it is the camera's protocol.
- With "Quick start from the watch" on, the phone listens for the watch on an encrypted Bluetooth serial channel. Only the one paired device you chose as your watch is served; anything else is disconnected at once. The watch can only ask for the ride state and to start or stop a ride. Anyone who controls that paired watch can start or stop your rides.
- Debug builds are signed with a debug key and are for testing only.
- Rides are kept in the app's external files folder, not in private internal storage, so they can be reached over USB when troubleshooting. On Android 10 and older, another app with storage permission can read them; on Android 11 and later it cannot. Accepted for now, and it may move to private storage later.

## Hardening in the app

No internet permission; the service is not exported; the file provider only exposes the `rides/` folder and a cache folder for temporary share copies; pending intents are immutable; backup and device transfer are off.
