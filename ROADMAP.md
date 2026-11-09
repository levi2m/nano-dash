# Roadmap

Nano Dash works end to end on one Osmo Nano and one phone. This lists what is proven, what is next, and what is deliberately left out.

## Proven

- Camera control over Bluetooth (pair, wake, set clock, start, stop), with reconnect and several cameras at once, tested against a fake camera and on one real Nano.
- Timing: first frame, clip end and clock offset measured on a real camera (see [docs/SYNC.md](docs/SYNC.md)).
- The `.fit` writer: our own encoder, parsed by two independent decoders, imported by Mimo.
- Real rides, with the phone locked in a pocket, imported in Mimo and lined up with their clips. A short ride on build 32 (the 360 ms clock offset, rounded seconds and phone-versus-GPS correction) lined up too.
- Public repository, a signed release from CI on every push to `main` (the build job holds the signing key with a read-only token, a separate publish job has the write token; actions pinned by hash, Gradle wrapper validated, a check that no shipped manifest has the internet permission, and a SHA-256 and signing-certificate fingerprint published with each release, for both the phone and the watch APK), and three audit rounds with their fixes. The last security audit had no confirmed findings and two points left to validate, listed in [SECURITY.md](SECURITY.md).
- A Wear OS remote over our own Bluetooth link: Start on the watch, hold to stop, tested end to end by the maintainer on a Galaxy Watch6 Classic with a Galaxy S25 Ultra (build 46). It is shipped as a second APK in every release.
- Targeting Android 17 (API 37), running on an API 37 phone. Accessibility: touch targets, labels, headings, light theme, large font and landscape checked, and a person confirmed TalkBack reads the phone's main screen well (build 35).

## Next

1. **A long ride with the screen off** (45 minutes or more), to see whether the phone's battery management ever stops the logger. The ride list now shows the longest wait between GPS fixes; a gap of minutes means the phone paused the app.
2. **F-Droid**, in progress: F-Droid builds from source and, as the build is reproducible, ships our own signed APK, so GitHub and F-Droid installs update over each other. The recipe (release 58) is in a fork of fdroiddata, and `fdroid lint` and `fdroid build`, including the reproducibility check, pass locally; the listing says the watch remote is optional and not part of the F-Droid build, and an F-Droid install hides the GitHub update link. Left: GitLab identity check for the pipeline, the merge request and the review (see [docs/FDROID.md](docs/FDROID.md)). The watch app stays GitHub-only. Google Play is a possible later step, see below.
3. **Wear OS remote:** built and working on a Galaxy Watch6 Classic: Start with a tap, Stop with a hold, the ride state, three haptic patterns and a TalkBack action. Design in [docs/WEAR_OS.md](docs/WEAR_OS.md) and [docs/WEAR_UI.md](docs/WEAR_UI.md). Left: a ride started from the watch with GPS fixes outdoors (not confirmed yet), TalkBack on the Settings sheet and the watch screens, other watches, the new tile on a real watch (built, not tested), an easier way to install it than ADB, and the watch's GPS as a second source only if it is wanted.
4. **The merge tool**, undecided: first check on a ride whether Mimo's lean and G widgets already work from the clip's own sensors. If they do, drop it. If not, combine the camera's orientation and acceleration (the clip's `djmd` track) with the phone's GPS into one `.fit`, keeping the GPS estimate as the fallback.
5. **Two cameras on a real ride:** connection order, Android's limit on parallel Bluetooth links, and a second camera's pairing. Waits for someone with two Nanos to file a compatibility report.
6. **Robustness, what is left:** a first-run battery-optimisation prompt, only if the long ride shows a need.
7. **Supply chain:** Gradle checks every downloaded library and plugin against a SHA-256 in `android/gradle/verification-metadata.xml`. Left: signature (PGP) checks, only if a concrete need appears.
8. **Engineering:** nothing planned. Split `LoggerService` or add a ViewModel only if a concrete problem appears.

## Possible later

- **Garmin remote** (lower priority, nothing built). The same Start and hold-to-stop remote for newer Garmin watches, as a Connect IQ app. Needs a different phone link from Wear OS; the plan is to support both Bluetooth LE (main route) and Garmin's proprietary Mobile SDK (second route, GitHub-only flavour). Plan and open questions in [docs/GARMIN.md](docs/GARMIN.md). First step: collect the watch model from a user who has one and check Garmin's current documentation; no code before that.
- **Google Play.** Not decided. It would need:
  - A Play Console developer account (one-time fee, identity verification). A new personal account has to run a closed test with opted-in testers for 14 days before production; check the current tester count in Play Console.
  - An Android App Bundle (`bundleRelease`) and Play App Signing. Play signs with its own key, so a Play install cannot update a GitHub or F-Droid install, or the other way round.
  - A Play build variant without the "Check for updates" button, because Play does not allow sending people elsewhere to download updates.
  - Store assets (512 px icon, 1024x500 feature graphic, at least two phone screenshots), a privacy policy URL (`PRIVACY.md` works), and the declaration forms: Data safety, content rating, ads, target audience, and the foreground-service declarations for the location and connected-device types, with a short demo video.
  - Care with the DJI name: nominative use only, no DJI logos, keep the unofficial disclaimer.
  - A Wear OS listing for the watch app (see [docs/WEAR_OS.md](docs/WEAR_OS.md)) is a separate, later step.
- **Android developer verification.** Google is rolling out identity verification for developers whose apps install on certified phones, sideloaded ones included, with Brazil among the first countries (reported for 30 September 2026; dates differ between sources and need checking on Google's pages). Registering in the Android Developer Console may be needed for the GitHub APK to keep installing, whether or not the app goes on Play.

## Left out, and why

- **Other DJI cameras** (Osmo Action line). They are reported to use DJI's R SDK framing, not the Nano's. That document and demo fall under DJI's developer licence, which this project avoids so it can be Apache-2.0 and go to F-Droid. Each model would also need its own timing measured. The scan lists only the Nano, so the app never sends Nano commands to a camera that does not speak them.
- **Replacing Mimo's other features** (camera settings, live preview, fetching clips over Wi-Fi). Possible to study by capturing Mimo's own traffic on a phone you own, but not planned. Mimo stays the place for overlays.
- **A map inside the app.** Map tiles need the internet permission and tell a tile server where you ride, which this app promises not to do. A ride's route opens in a maps app as GPX instead.
- **Phone microphone to hear the start beep.** It sees only what the app already tracks over Bluetooth and would add a microphone permission to an app that promises not to leak anything.

## Release checklist

- [x] Licence Apache-2.0; `NOTICE` credits Osmosis and states the project is unofficial.
- [x] Safety wording in the README and `NOTICE`; no DJI SDK or Garmin SDK material in the tree.
- [x] Fresh `git init`; keep `PLAN.md`, `footage/`, `*.fit`, `*.mp4` and trial logs out; search the tree for personal data before the first commit.
- [x] Python tools in `tools/`: short README, no personal paths.
- [x] Signed release APK (built by CI); the release keystore backed up by the maintainer (losing it means no update can install over the release).
- [ ] F-Droid: reproducible recipe for release 58 committed to the fork; `fdroid build` matches the GitHub APK locally; the merge request to fdroiddata is not filed yet.
- [x] Application id `io.github.levi2m.nanodash`, fixed once published.
