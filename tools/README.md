# Research tools

Scripts used to measure the Osmo Nano and to check the app's output. They are not part of the app; the Kotlin in `android/core` is the reference.

- `ble-spike/nano_ble.py`: talk to a Nano from a PC over Bluetooth (pair, wake, record, timing trials). Needs `bleak`.
- `ble-spike/set_clock_trials.py`, `clip_header.py`: set the camera's clock on a second boundary, record test clips, read their header times. This is how the clock offset in [docs/SYNC.md](../docs/SYNC.md) was measured.
- `ble-spike/status_probe.py`: dump what the camera sends by itself.
- `ble-spike/analyze_trials.py`, `flash_window.py`: the screen-flash method for the first-frame and clip-end delays.
- `ble-spike/validate_app_clips.py`: compare the app's logged clip windows with real clips.
- `validate_fit.py`: check a `.fit` with fitparse and, if installed, Garmin's decoder (never shipped).

Trial logs go to `ble-spike/trials/`, which is git-ignored: they hold timings and, with a ride, locations.
