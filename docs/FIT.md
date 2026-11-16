# The .fit file

Written by our own encoder (`android/core/.../fit/`), from the public FIT file-format description: header, definition and data messages, CRC-16. No Garmin SDK is used or included. Output is cross-checked with two independent decoders (fitparse, MIT, and Garmin's own decoder, used locally only) by `tools/validate_fit.py`.

## Messages

`file_id` (activity, real creation time), `developer_data_id`, four `field_description`, `record` (1 Hz), `session` (sport motorcycling, 22) and `activity`.

## Record fields

Timestamp (rounded to the nearest second, on the camera's clock; see SYNC.md), latitude, longitude, altitude (sea level on Android 14+; fixes with vertical accuracy worse than 20 m are ignored), distance, speed, grade, and the enhanced speed and altitude fields. These are the fields Mimo's own export has, and Mimo reads speed, elevation, distance and the track from them.

## Heart rate (optional)

When the track folder has a `health.csv` (added from Health Connect, see [HEALTH.md](HEALTH.md)), each record also has `heart_rate` (field 3, bpm, invalid where no reading is within 5 s) and the session has `avg_heart_rate` and `max_heart_rate`. A track without readings has none of these fields. Shown by Mimo on one track (see HEALTH.md).

## Developer fields

Application id `nano-dash-logger`:

| Field | Unit | Meaning |
| --- | --- | --- |
| `lean_angle` | deg | Steady-state estimate, `atan(lateral acceleration / g)`, positive to the right |
| `lateral_accel` | m/s² | Speed times heading rate |
| `longitudinal_accel` | m/s² | Change of speed per second |
| `camera_recording` | bool | 1 while the camera was recording |

Lean and accelerations come from the GPS track alone: they ignore suspension, tyre profile and user position, so treat them as estimates. Mimo draws its own lean and G widgets; their values did not match these fields in one check, so they probably come from the clip's sensors.

## Track folder

Per track, in the app's own folder under `Android/data/<application id>/files/rides` (removed with the app): `gnss.csv` (one line per fix, including `v_acc_m`), `imu.csv`, `events.jsonl` (camera commands with both clocks), `meta.json` (`format_version`, clock anchors, counts, `altitude_reference`, `phone_minus_gps_ms`, and per camera its start result, clip window and `clock_lag_ms`, the camera clock's lag alone; `events.jsonl` keeps `fit_shift_ms`, the lag plus the phone-versus-GPS skew, which is what the .fit is shifted by), `route.txt` (the thinned route, the longest GPS gap, the distance and the top speed, cached for the track list), `health.csv` (optional: the heart-rate readings added from Health Connect, one `utc_ms,heart_rate_bpm` line each, see [HEALTH.md](HEALTH.md)) and `ride.fit` (or one `ride-<camera>.fit` per camera clock). The `.fit` files can be rebuilt from `gnss.csv` and `events.jsonl`; the app does it by itself for a track that has GPS fixes but no `.fit`.
