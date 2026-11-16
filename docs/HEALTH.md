# Heart rate from Health Connect

Idea: a watch (here a Galaxy Watch 6 Classic with Samsung Health) records heart rate during the track, and Nano Dash writes it into the track's `.fit` so that DJI Mimo's dashboard can show it with the clip. This file says what is built, what is proven, and what is not.

**Status: works on one phone and one watch; little else is proven.** On 16 November 2026 a Galaxy S25 Ultra with a Galaxy Watch 6 Classic (Samsung Health) filled in tracks, and Mimo drew the heart rate over the clip of one of them (below). Everything not listed under "Observed" is untested.

## How it works

1. During the track nothing changes. The phone logs GPS and motion as before; the watch records its own workout.
2. After the track, on a track card, the user taps **Add health data** once. Android shows Health Connect's permission screen for *read heart rate* (it cannot be skipped; only a tap can open it). Nothing else is asked, and the app never writes to Health Connect. From then on it is automatic: whenever the app is open, a track that ended within the last 3 days, has a `.fit` and has no heart rate yet (at most 5 tracks per pass) is looked up and filled in without a tap. The watch may sync late, so a track that found nothing is tried again each time the app is opened and after each track, for 3 days. The automatic pass is silent when it finds nothing or fails; the button explains why. Nothing happens while the app is closed (that would need a second, background permission, which the app does not ask for).
3. The app asks Health Connect for heart-rate records starting from 1 hour before the first GPS fix (a watch may write one long record, and the matching rule is unchecked), and keeps every sample between 10 s before the first fix and 10 s after the last one, from any app that wrote them.
4. The samples are saved in the track folder as `health.csv` (`utc_ms,heart_rate_bpm`), and the `.fit` is rebuilt from the folder, like any rebuild.
5. **Update health data** reads again (for example after the watch synced late). Removing the readings, by deleting `health.csv` and rebuilding, gives back byte for byte the `.fit` the track had before.

Code: `core/ride/HealthData.kt` (the file and the merge), `core/fit/FitActivityWriter.kt` (the field), `app/.../HealthConnectReader.kt` (the read), `app/.../HealthRationaleActivity.kt` (the screen Health Connect requires).

## What goes in the `.fit`

- Each one-second record gets `heart_rate` (field 3, bpm) from the nearest reading **within 5 s**. With no reading that close the field is the FIT "invalid" value, so a gap in the watch's data stays a gap. Nothing is interpolated.
- The session gets `avg_heart_rate` and `max_heart_rate` (fields 16 and 17).
- A track with no readings has none of these fields: its file is unchanged from before this feature (a test checks it).
- Readings follow the same time shift as the GPS records (the camera's lag, see [SYNC.md](SYNC.md)), so heart rate and position stay together.
- Readings outside 30 to 250 bpm are dropped as sensor glitches.

## Android and privacy

- Uses the platform API of Android 14 and later (`android.health.connect`), so no new dependency and no Google Play services. Health Connect is an Android 14 extension (level 7), checked at run time. Older phones do not see the button.
- Declares one permission, `android.permission.health.READ_HEART_RATE`, and one exported entry for the privacy screen Health Connect requires: an `activity-alias` (action `VIEW_PERMISSION_USAGE`, category `HEALTH_PERMISSIONS`) that only the system can start, pointing at `HealthRationaleActivity`, which is not exported itself. Android 14 and later need the alias: without it Health Connect does not list the app or show the request (found on the S25 Ultra). The screen shows text only and reads nothing from the intent. `ManifestPolicyTest` pins all of it. (Whether the privacy-policy link in Health Connect still opens the screen with the activity not exported is unchecked.)
- Still no internet permission (`scripts/check-no-internet.sh` passes on the debug and release manifests).
- By default Health Connect gives an app data from up to 30 days before the permission was first granted. Old tracks may therefore need the permission granted early; unchecked.

## Observed (16 November 2026, Galaxy S25 Ultra, Galaxy Watch 6 Classic, Samsung Health)

- **Mimo shows the heart rate.** The `.fit` of a track (sport motorcycling, as every Nano Dash `.fit` declares) was imported with its clip, and Mimo drew a Heart Rate widget (122 BPM at 21:23:33). Seen once, on one Mimo version.
- **Samsung writes dense data.** A 14-minute track got 865 readings, about one a second, 94 to 142 bpm, the longest gap 3.7 s; another track got 830. A third track (20:52) got only 7: no exercise was recorded on the watch then (per the user), so Health Connect held only the watch's background heart-rate readings. The app reads those too, by time, so a track gets heart rate whether or not a workout was recorded, but only sparsely without one.
- Permission request: the system screen only appears with the Android 14 alias; see above.

## Not proven (label as such)

- **Whether Mimo shows heart rate on other versions or for other tracks.** One import is not a test.
- **The 5 s window.** It worked on the track above, but it is a choice, not a measurement.
- **How Health Connect matches a long heart-rate record to the time range** (by its start or by overlap): the documentation does not say, so the query starts 1 hour early as a guard. Whether that was needed is unknown.
- **Offline behaviour** of the whole flow.
- Any clock difference between the watch and the phone has not been measured. It is assumed small; the phone-versus-GPS difference is applied to the readings like it is to the camera spans.
