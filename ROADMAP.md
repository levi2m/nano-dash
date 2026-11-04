# Roadmap

Nano Dash works end to end on one Osmo Nano and one phone. This lists what is proven, what is next, and what is deliberately left out.

## Proven

- Camera control over Bluetooth (pair, wake, set clock, start, stop), with reconnect and several cameras at once, tested against a fake camera and on one real Nano.
- Timing: first frame, clip end and clock offset measured on a real camera (see [docs/SYNC.md](docs/SYNC.md)).
- The `.fit` writer: our own encoder, parsed by two independent decoders, imported by Mimo.
- Real rides, with the phone locked in a pocket, imported in Mimo and lined up with their clips. A short ride on build 32 (the 360 ms clock offset, rounded seconds and phone-versus-GPS correction) lined up too.
- Public repository, a signed release from CI on every push to `main` (a separate job holds the signing key; actions pinned), and two security audits with their fixes.
- Targeting Android 17 (API 37), running on an API 37 phone. Accessibility: touch targets, labels, headings, light theme, large font and landscape checked, and a person confirmed TalkBack reads it well (build 35).

## Next

1. **A long ride with the screen off** (45 minutes or more), to see whether the phone's battery management ever stops the logger. The ride list now shows the longest wait between GPS fixes; a gap of minutes means the phone paused the app.
2. **F-Droid**, in progress: F-Droid builds from source and signs with its own key, so a GitHub APK and an F-Droid APK cannot update over each other. Needs its metadata file and a clean build check. Google Play is a possible later step, see below.
3. **Wear OS remote:** built as a phone-only feature (build 36): an opt-in "Quick start from the watch" notification with a Start button, plus the existing Stop button, both mirrored by the watch's notification bridge. Design and findings in [docs/WEAR_OS.md](docs/WEAR_OS.md). To confirm on a real watch; a watch app only if the mirroring is not enough.
4. **The merge tool**, undecided: first check on a ride whether Mimo's lean and G widgets already work from the clip's own sensors. If they do, drop it. If not, combine the camera's orientation and acceleration (the clip's `djmd` track) with the phone's GPS into one `.fit`, keeping the GPS estimate as the fallback.
5. **Two cameras on a real ride:** connection order, Android's limit on parallel Bluetooth links, and a second camera's pairing. Waits for someone with two Nanos to file a compatibility report.
6. **Robustness, what is left:** a first-run battery-optimisation prompt, only if the long ride shows a need.
7. **Engineering:** nothing planned. Split `LoggerService` or add a ViewModel only if a concrete problem appears.

## Possible later

- **Google Play.** Not decided. It would need:
  - A Play Console developer account (one-time fee, identity verification). A new personal account has to run a closed test with opted-in testers for 14 days before production; check the current tester count in Play Console.
  - An Android App Bundle (`bundleRelease`) and Play App Signing. Play signs with its own key, so a Play install, a GitHub APK and an F-Droid install cannot update over each other.
  - A Play build variant without the "Check for updates" button, because Play does not allow sending people elsewhere to download updates.
  - Store assets (512 px icon, 1024x500 feature graphic, at least two phone screenshots), a privacy policy URL (`PRIVACY.md` works), and the declaration forms: Data safety, content rating, ads, target audience, and the foreground-service declarations for the location and connected-device types, with a short demo video.
  - Care with the DJI name: nominative use only, no DJI logos, keep the unofficial disclaimer.
  - A Wear OS listing only if a watch app is ever built. With the notification design in [docs/WEAR_OS.md](docs/WEAR_OS.md) there is no watch app to publish.
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
- [ ] F-Droid: builds from source with free-software dependencies only (true now); metadata file and a clean build check pending. F-Droid signs with its own key.
- [x] Application id `io.github.levi2m.nanodash`, fixed once published.
