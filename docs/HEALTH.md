# Heart rate from Health Connect

Idea: a watch (here a Galaxy Watch 6 Classic with Samsung Health) records heart rate during the ride, and Nano Dash writes it into the ride's `.fit` so that DJI Mimo's dashboard can show it with the clip. This file says what is built, what is proven, and what is not.

**Status: built, not tested on a device, and Mimo's behaviour is unknown.** Nothing below about Mimo or Samsung is confirmed yet.

## How it works

1. During the ride nothing changes. The phone logs GPS and motion as before; the watch records its own workout.
2. After the ride, on a ride card, the rider taps **Add heart rate** once. Android shows Health Connect's permission screen for *read heart rate* (it cannot be skipped; only a tap can open it). Nothing else is asked, and the app never writes to Health Connect. From then on it is automatic: whenever the app is open, a ride that ended within the last 3 days, has a `.fit` and has no heart rate yet (at most 5 rides per pass) is looked up and filled in without a tap. The watch may sync late, so a ride that found nothing is tried again each time the app is opened and after each ride, for 3 days. The automatic pass is silent when it finds nothing or fails; the button explains why. Nothing happens while the app is closed (that would need a second, background permission, which the app does not ask for).
3. The app asks Health Connect for heart-rate records starting from 1 hour before the first GPS fix (a watch may write one long record, and the matching rule is unchecked), and keeps every sample between 10 s before the first fix and 10 s after the last one, from any app that wrote them.
4. The samples are saved in the ride folder as `health.csv` (`utc_ms,heart_rate_bpm`), and the `.fit` is rebuilt from the folder, like any rebuild.
5. **Update heart rate** reads again (for example after the watch synced late). Removing the readings, by deleting `health.csv` and rebuilding, gives back byte for byte the `.fit` the ride had before.

Code: `core/ride/HealthData.kt` (the file, the merge, the sport), `core/fit/FitActivityWriter.kt` (the field), `app/.../HealthConnectReader.kt` (the read), `app/.../HealthRationaleActivity.kt` (the screen Health Connect requires).

## What goes in the `.fit`

- Each one-second record gets `heart_rate` (field 3, bpm) from the nearest reading **within 5 s**. With no reading that close the field is the FIT "invalid" value, so a gap in the watch's data stays a gap. Nothing is interpolated.
- The session gets `avg_heart_rate` and `max_heart_rate` (fields 16 and 17).
- A ride with no readings has none of these fields: its file is unchanged from before this feature (a test checks it).
- Readings follow the same time shift as the GPS records (the camera's lag, see [SYNC.md](SYNC.md)), so heart rate and position stay together.
- Readings outside 30 to 250 bpm are dropped as sensor glitches.
- The session's sport is **motorcycling** by default. The ride card has a **Switch sport** button (after readings are added) that toggles to **cycling** and rebuilds. It is stored in `sport.txt` in the ride folder.

## Android and privacy

- Uses the platform API of Android 14 and later (`android.health.connect`), so no new dependency and no Google Play services. Health Connect is an Android 14 extension (level 7), checked at run time. Older phones do not see the button.
- Declares one permission, `android.permission.health.READ_HEART_RATE`, and one extra exported component, `HealthRationaleActivity`, which Health Connect opens to show why the app reads heart rate. It shows text only and reads nothing from the intent. `ManifestPolicyTest` pins both.
- Still no internet permission (`scripts/check-no-internet.sh` passes on the debug and release manifests).
- By default Health Connect gives an app data from up to 30 days before the permission was first granted. Old rides may therefore need the permission granted early; unchecked.

## Not proven (label as such)

- **Whether Mimo shows heart rate from a `.fit`**, and for which sport. The probe files below exist to find out; the answer is not known.
- **The 5 s window.** It is a choice, not a measurement.
- **Whether Samsung Health writes dense heart rate to Health Connect** during a workout, or only a sparse summary. Unchecked. If it is sparse, the 5 s window leaves most records empty.
- **Offline behaviour of Health Connect**, and the app on a real device: the read code has been compiled and linted, not run.
- Any clock difference between the watch and the phone has not been measured. It is assumed small.

## Probe files

To test Mimo without real data, two synthetic rides were generated with `FitActivityWriter` by a throwaway test that is not in the repository: a loop with heart rate between 60 and 160 bpm, once as motorcycling and once as cycling. Import both with any clip and see which one shows a heart-rate widget. They hold no real location and are not kept in the repository.
