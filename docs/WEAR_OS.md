# Wear OS remote: design

Status: proposal, nothing built. The roadmap item is "a start/stop remote on the watch, with the phone still holding the camera link".

## 1. What is needed, and what is not

**Need:** while the phone is locked in a pocket, stop the ride (and, if possible, start it) from the wrist, with the phone still doing the GPS, sensors and camera link.

**Not needed for a first version:** the watch's own GPS, any data stored on the watch, a watch UI beyond a button, any network.

**Constraints that come from the app's promises:**
- No internet permission, no accounts, no analytics.
- Apache-2.0 and F-Droid friendly, so no dependency on Google Play services or other non-free libraries inside this app.
- The phone keeps the camera link. The watch never talks to the camera.
- A stray tap must not quietly wreck a ride.

## 2. Options

| | What it is | Dependencies | Cost | Verdict |
|---|---|---|---|---|
| A | The ride notification on the phone is mirrored to the watch by Wear OS, with its action buttons (Stop, and optionally Start) | None in this app. The mirroring is done by the Wear OS companion app, which needs Google services on the phone and watch | A few lines in the phone app, no watch app | **Do this first** |
| B | A real watch app (Wear Compose, a tile) sending commands over Google's Wearable Data Layer | `play-services-wearable`, which is proprietary | A second app, a second build and release, and it cannot go to F-Droid | Only if A is not enough, and only as a GitHub-only extra |
| C | A real watch app talking to the phone over its own Bluetooth link | None, but needs a pairing secret and a GATT server on the phone | The most code and the most security work | Not justified for a button |
| D | Watch GPS as a second source | Needs B or C plus clock sync and merging | Large | **No**: the phone's GPS already works, and a second source adds sync error |

The deciding question is whether A already gives a usable remote. It may, because the app already shows an ongoing notification with a **Stop** action (`LoggerService.kt:450-460`, immutable `PendingIntent`, explicit service target).

## 3. Recommended path

**Stage 0, a spike (no code):** on the real watch, start a ride and see what the watch shows. Questions to answer:
1. Does the ongoing ride notification appear on the watch, with its Stop button?
2. Is Stop reliable with the phone locked and the screen off?
3. Does it work on API 37 with the app in the background?

**Stage 1, phone only, if Stage 0 mostly works:** make the notification a good watch citizen.
- Set `setLocalOnly(false)` and add a `WearableExtender` with a clear "Stop ride" label, so mirroring is explicit and not left to a default.
- Handle `ACTION_START` explicitly in `onStartCommand` (today any non-`ACTION_STOP` intent starts a ride; it is unreachable from other apps because the service is not exported, but it should say what it means).
- Keep the start path as it is: you start a ride on the phone, where you already approve permissions and see the camera status.

**Stage 1b, optional, only if you want Start from the wrist:** an opt-in setting "Quick start from the watch" that shows a low-priority "Ready" notification while idle, with a **Start ride** action (`PendingIntent.getForegroundService`, immutable, explicit). Cost: a notification that sits there while the setting is on. Open question for the spike: whether a location foreground service may be started from a notification action while the app is in the background. Android allows a foreground-service start after the user acts on a notification, but the "while-in-use" location rule has its own exceptions. This has to be tried on the API 37 phone before anything is promised.

**Stage 2, only if A cannot work:** a watch app. If so, choose B as a separate, GitHub-only watch APK, so the phone app and its F-Droid listing stay free of Google dependencies.

## 4. Data flow for Stage 1

```
 wrist tap "Stop ride"
        |
        v
 Wear OS companion (Google) mirrors the tap
        |   (system carries the notification action; not our code)
        v
 phone: PendingIntent (immutable, explicit) -> LoggerService.onStartCommand(ACTION_STOP)
        |
        v
 stopRide(): stop sensors and GPS, camera stop, write .fit        (unchanged)
        |
        v
 notification updates to "Saving the ride", then removes itself   (unchanged)
```

No new process, no new permission, no new network use. The only new code is in how the notification is built.

## 5. Failure modes and how they are handled

| Situation | What happens | Decision |
|---|---|---|
| Accidental tap on Stop mid-ride | The ride ends and is saved as a short ride; the camera clip stops with it | Accept. A confirmation step would need a second notification or an activity, which is more than the risk justifies. The ride is not lost, and a new one can start. |
| Watch out of range or no mirroring | The notification is only on the phone; nothing breaks | Phone behaves exactly as today. |
| Start tapped with no location permission or GPS off | The service shows its existing message and stops | Reuse the existing checks; no new code path. |
| Start tapped while a ride is saving | Ignored by the existing `saving` guard | Reuse. |
| Duplicate taps | The second is a no-op because the session is already null or already running | Reuse. |

## 6. Security

- The action is an immutable `PendingIntent` to an explicit component. Other apps cannot trigger it. Only the system, and notification listeners the user installed (the Wear OS companion), can fire notification actions. That companion is already part of the user's trust base, since it sees every notification.
- No new exported component, no new permission, and the "no internet" promise is unchanged.
- Stage 2 (if ever built) would need a pairing secret and replay protection. That is why it is last.

## 7. Tests

- Core logic does not change, so core tests do not change.
- Add a manifest-policy assertion that nothing new is exported (the existing `ManifestPolicyTest` already checks the exported set).
- The behaviour on a real watch can only be checked by hand: the Stage 0 questions above, repeated on any Stage 1 build.

## 8. What would change this design

- If the spike shows the Stop action does not mirror reliably, Stage 2 becomes real work and the choice between B and C has to be made then.
- If more than one person wants Start from the wrist, Stage 1b becomes worth its idle notification.
- If a watch GPS or heart-rate source is ever wanted, that is a separate project with its own design (time sync and merging), not an extension of this one.

## 9. Open questions

1. Which watch, and does it already show the ride notification with a Stop button? (Stage 0.)
2. Is Start from the wrist wanted, or is Stop enough?
