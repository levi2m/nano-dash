# Lining the .fit up with the video

Mimo attaches a `.fit` to a clip by absolute time: the clip's header time (the camera's clock, whole seconds) must fall inside the file's records. A file from another day is refused.

Three clocks are involved: the GPS (UTC, the source of the `.fit` records), the phone's clock (stamps the camera commands), and the camera's clock (stamps the clip). The work is relating them.

## Where the clip sits in phone time

Measured on one Osmo Nano with a screen-flash method (the camera films a full-screen flash while the phone logs when it drew it), then confirmed without a display by a shake test:

| Constant | Value | Spread |
| --- | --- | --- |
| First frame after the camera's status first reports recording | 255 ms | sd 28 ms (5 clips) |
| Clip end before the stop command is sent | 651 ms | sd 11 ms |

The shake test held phone and camera together. The phone's gyroscope speed against the camera's angular speed (from the orientation quaternions in the clip's metadata track) correlated at 0.97, with the true first frame 35 ms after the estimate. Predicted clip lengths matched actual ones to about 50 ms over six clips. Code: `CameraTiming`.

## The camera's clock

The camera's offset from the phone differs from unit to unit: the development Nano, set earlier by Mimo, ran 1.45 to 1.81 s behind. So the app sets the camera's clock on every connect, over Bluetooth: command `00/6A` to the clock receiver `0x28` (documented by Osmosis, accepted by the Nano) with unix seconds, UTC offset and zone id. The camera takes whole seconds, so the write is timed to arrive 25 ms before a second boundary of the phone's clock. A camera that is already recording (a link that dropped mid-ride) is not set again.

Measured with a PC as reference: eight test clips over three separate sets, one after a power cycle, all agree that the camera then sits 357 to 361 ms behind. The clip header has 1 s resolution, so the starts were spread across the second to pin it down. The writer moves the records 360 ms earlier (`CameraTiming.CLOCK_BEHIND_PHONE_MS`). A camera that does not accept the command keeps the old 1.7 s value, and so do ride logs written before this (`fit_shift_ms` in the events). This assumes the phone's Bluetooth write delay is close to the PC's that was measured. A ride will show whether it is; if not, the constant is one line.

## The phone's clock against the GPS

The records are GPS time but the camera's clock came from the phone. If the phone's clock is off, the overlay is off by the same amount. Every fix carries its GPS time and the phone's monotonic clock, and the ride's start recorded both the phone's UTC clock and the monotonic clock. At the end of a ride the median of (phone clock minus GPS time) is added to the shift and written to `meta.json` and the camera events as `phone_minus_gps_ms`. Fewer than five fixes, or a difference over two minutes, is ignored. The app warns when the phone's automatic time is off.

## Seconds

A `.fit` timestamp is a whole second. Records are rounded to the nearest second, not cut: cutting labels each sample half a second early on average, and turns a 360 ms shift into a full second for fixes that land on a whole second.

## Measuring a camera's clock (for another model)

How the 357 to 361 ms was found. Run `tools/ble-spike/set_clock_trials.py` (sets the clock on a second boundary, then records three clips with their first frames spread across the second), then read each clip's creation time with `tools/ble-spike/clip_header.py`. Header times are UTC, whole seconds, about one second after the time in the file name. Each clip says the lag lies between (first frame in your clock − header − 1 s) and (first frame − header). The intersection of the three windows is the answer. `tools/ble-spike/status_probe.py` dumps what the camera sends by itself; it showed no clock in any message.

## Real rides

Three rides on 2026-11-03, made with the earlier 1.7 s shift and truncated seconds: the file's camera window started 1 to 2 s after the clip header and ended within 1 s of the clip's end, which is the header's resolution. The 360 ms offset, rounding and the GPS correction have not been checked on a ride yet.

## Limits

One camera, one phone. The camera-command times come from the phone's clock; the GPS correction above is the only check on it.
