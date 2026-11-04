# Security

## Reporting

Please report security problems privately through GitHub's "Report a vulnerability" on this repository, not as a public issue.

## Known and accepted

- The Osmo Nano's Bluetooth control link is unencrypted by DJI's design and the pairing identifier travels in the clear. A nearby device that overhears it could control the camera. Nano Dash cannot fix this; it is the camera's protocol.
- Debug builds are signed with a debug key and are for testing only.

## Hardening in the app

No internet permission; the service is not exported; the file provider only exposes the `rides/` folder and a cache folder for temporary share copies; pending intents are immutable; backup and device transfer are off.
