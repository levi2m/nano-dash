# Wear OS remote: design

Status: built and working (build 40): the armed phone service, the Bluetooth link, and the watch app (see section 13). The notification-only route from section 3 stays as a fallback. The roadmap item is "a start/stop remote on the watch, with the phone still holding the camera link".

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

Historical: sections 3 to 9 are the plan made before building. Stage 1 and 1b were built (section 10), Stage 0 showed the notification does not reach the watch, and sections 11 to 14 are what shipped.

**Stage 0, a spike (no code):** on the real watch, start a ride and see what the watch shows. Questions to answer:
1. Does the ongoing ride notification appear on the watch, with its Stop button?
2. Is Stop reliable with the phone locked and the screen off?
3. Does it work on API 37 with the app in the background?

**Stage 1, phone only, if Stage 0 mostly works:** make the notification a good watch citizen.
- Set `setLocalOnly(false)` and add a `WearableExtender` with a clear "Stop ride" label, so mirroring is explicit and not left to a default.
- Handle `ACTION_START` and `ACTION_STOP` explicitly in `onStartCommand` (done in build 36: an intent with any other action no longer starts a ride).
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

## 9. Open questions (answered)

1. Which watch, and does it show the ride notification with a Stop button? A Galaxy Watch6 Classic; it did not show it (section 11).
2. Is Start from the wrist wanted? Yes, so the watch app has both (section 13).

## 10. What was built and what was found (build 36)

**Built:**
- The ride notification is explicitly mirrorable (`setLocalOnly(false)`), and `ACTION_START` and `ACTION_STOP` are handled by name: a stray intent no longer starts a ride.
- **Quick start from the watch** (off by default, a switch at the bottom of the screen): while it is on, a silent ongoing notification "Nano Dash is ready" with a **Start ride** button stays on the phone whenever no ride is running. It uses the same notification id as the ride notification, so starting a ride replaces it in place, and it comes back when the ride ends.

**Tested on the phone (Galaxy S25 Ultra, Android 17 / API 37, app in the background, not opened):**
- Tapping **Start ride** in the notification started the ride service as a foreground service with the location and connected-device types. No error in the log, a ride folder was created, and the service registered for 1 Hz high-accuracy GPS.
- Tapping **Stop** in the ride notification ended it, and the Ready notification came back.
- This answers the open question from section 3: the platform lets a notification button start a location foreground service from the background on this phone.

**Still to confirm:**
- That the **watch** (a Galaxy Watch6 Classic, with Samsung's Galaxy Wearable companion) mirrors the notification and shows the Start and Stop buttons.
- That a ride started this way receives real GPS fixes outdoors. The test was indoors, so it proved the service starts and registers for location, not that fixes arrive.
- The behaviour on other phones: Android's rules for starting a foreground service from a notification are the same across versions, but manufacturers differ.

## 11. Decision: a real watch app over our own Bluetooth link

The notification route was tried first (build 36). On the Galaxy Watch6 Classic nothing showed up on the watch, and a notification is a poor remote anyway: it is buried on a watch. So the roadmap item becomes a watch app. The choices, made with the maintainer:

- **Transport:** our own Bluetooth link, not Google's Data Layer. The phone app keeps no Google dependency and stays F-Droid-clean.
- **Watch app:** one screen with Start and Stop, the ride state, and a tile for one-swipe access.
- **Installing it:** a second APK in each GitHub release, installed on the watch over wireless debugging. F-Droid cannot install onto a watch.

### How the link works

The watch and the phone are already paired by Bluetooth. The phone opens a **secure RFCOMM** service (a Bluetooth serial channel) with the fixed id `a3286e7a-3551-4008-abcb-d4c7c9fbf582`. "Secure" means Android encrypts and authenticates it with the existing pairing, so no new secret is needed. The watch connects to it.

- **Only the user's watch is accepted.** In the phone app the user picks which paired device is their watch. Any other device that connects is closed at once.
- **Protocol:** text lines, at most 128 bytes, each ended by a newline. The watch sends `HELLO 1`, `STATUS`, `START` and `STOP`. The phone answers `OK` or `ERR <reason>` and sends `STATE <ride> <seconds> <gps> <camera>` when something changes and every 2 seconds while a ride runs. Unknown lines are ignored. A line over the limit closes the connection.
- **The same protocol code** (in `core`) is used by both apps, so they cannot drift apart.

### Why the phone service has an "armed" mode

The phone has to be listening while the app is closed, and Android does not let an app start a location service from the background on its own. The quick-start test showed a notification button is allowed, but a Bluetooth message is not on that list. So the phone starts its ride service **while the app is on screen** (when the user switches "Watch remote" on), and that service waits in an armed state: a foreground notification, the Bluetooth listener, and no GPS or sensors. A Start from the watch then only changes the state of a service that is already running, so no new foreground service has to be started from the background. Stop returns it to armed. Switching it off stops the service.

Cost: one quiet notification while it is on, and it must be switched on again after a restart of the phone.

### Safety on the watch

- Stop needs a **press and hold**, so a stray tap cannot end a ride. TalkBack users get a "Stop ride" action instead of the hold (see [WEAR_UI.md](WEAR_UI.md)).
- Start is a single tap.
- If the phone is out of reach, the watch shows "Phone not reachable" and does nothing else.

### Milestones

1. **Spike:** the smallest watch app that connects to the phone and gets a reply. Proves the Galaxy Watch allows the link. Nothing else is built until this works.
2. Armed mode and the Bluetooth listener on the phone, with the protocol in `core` and its tests.
3. The watch screen: Start, Stop (hold), state.
4. The tile.
5. CI builds and releases both APKs; the docs and the roadmap follow.

## 12. Milestone 1 result: the link works

The spike (a minimal watch app and the phone's listener) was tested on a Galaxy S25 Ultra (Android 17) and a Galaxy Watch6 Classic (SM-R965F, Android API 36). The watch app found the paired phone, opened the secure Bluetooth serial channel, sent `HELLO 1` and `STATUS`, and the phone answered `OK` and `STATE idle 0 off none`.

Two things the spike taught:
- Do not call `cancelDiscovery()` on the watch: it needs the Bluetooth scan permission and nothing here scans. The watch app only needs the Bluetooth connect permission.
- The on-watch screen is tiny, so every message must be short.

Next: milestone 2, the armed phone service and the real handler.

## 13. Built and tested (build 40)

- **Phone:** the service has an armed mode (a "Nano Dash is ready" notification, no GPS, a Bluetooth listener) switched by "Quick start from the watch". It serves only the paired device the rider chose, answers `STATUS`, and turns `START` and `STOP` into a real ride on the main thread. A start is refused with a reason (`gps-off`, `no-permission`, `busy`) when it cannot happen. The phone app asks for location, Bluetooth and notification permission the first time.
- **Watch:** `:wear` is a Wear OS 4 app that reaches the phone over the paired link, polls its state every 1.5 seconds and reconnects by itself. Its screens follow [WEAR_UI.md](WEAR_UI.md).
- **CI** builds both APKs and attaches them to every release: `nano-dash-<tag>.apk` for the phone and `nano-dash-wear-<tag>.apk` for the watch.
- **Installing the watch app:** enable developer mode on the watch, turn on Debug over Wi-Fi, then `adb connect <watch ip:port>` and `adb install nano-dash-wear-<tag>.apk`. F-Droid cannot install onto a watch.
- **Tested** by the maintainer on a Galaxy Watch6 Classic with a Galaxy S25 Ultra: everything worked.

**Still open:** the tile, a simpler way to install the watch app, the behaviour on other watches, and whether a ride started from the watch receives GPS fixes outdoors (the service type is chosen to allow it, but only a ride outdoors proves it).

## 14. Getting the watch app onto the watch (decision)

Wear OS has no sideload installer and only Google Play or ADB can install an app, so the phone app cannot push the APK. An in-app ADB client would need the INTERNET permission, which breaks the no-network promise. So the phone app carries a guide instead: a "Watch app" row under the quick start toggle shows "not seen yet" or "connected before" (the time the watch last finished the handshake is stored in the app's own preferences) and opens the install steps with a link to the latest release. No new permission. A watch-only store listing stays an option for later.
