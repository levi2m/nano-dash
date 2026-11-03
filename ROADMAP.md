# Roadmap

Nano Dash works end to end on one Osmo Nano and one phone. This lists what is proven, what is next, and what is deliberately left out.

## Proven

- Camera control over Bluetooth (pair, wake, set clock, start, stop), with reconnect and several cameras at once, tested against a fake camera and on one real Nano.
- Timing: first frame, clip end and clock offset measured on a real camera (see [docs/SYNC.md](docs/SYNC.md)).
- The `.fit` writer: our own encoder, parsed by two independent decoders, imported by Mimo.
- Three real rides, with the phone locked in a pocket, imported in Mimo and lined up with their clips.

## Next

1. **Check the new settings on a ride.** The 360 ms offset on the phone's Bluetooth, rounded seconds and the phone-versus-GPS correction are built and unit-tested but not yet seen in Mimo.
2. **A long ride with the screen off** (45 minutes or more), to see whether the phone's battery management ever stops the logger.
3. **Two cameras on a real ride:** connection order, Android's limit on parallel Bluetooth links, and a second camera's pairing.
4. **Robustness:** rebuild a missing `.fit` automatically on launch, a first-run prompt for battery optimisation on Samsung and similar phones, an "export all rides" action (rides are removed with the app), `format_version` in `meta.json`, target API 37, and checks of the light theme, large fonts, TalkBack and landscape.
5. **Engineering:** done: version catalog, R8 release build (signed once `android/keystore.properties` exists), a GitHub Actions workflow (written, not run yet), and the testable app logic moved into `core` (80 tests). Left: only if a concrete problem appears, split `LoggerService` or add a ViewModel.
6. **Publish:** a fresh git repository (not this folder's history), `PLAN.md` kept out, a signed APK on GitHub, then F-Droid. Google Play is probably not happening.
7. **The merge tool:** combine the camera's own orientation and acceleration (the clip's `djmd` track) with the phone's GPS into one `.fit`. The camera is rigid on the bike, so it is the better lean source; the GPS estimate stays as the fallback.
8. **Wear OS companion**, after the phone app is finished: a second GPS source and a start/stop remote, with the phone still holding the camera link.

## Left out, and why

- **Other DJI cameras** (Osmo Action line). They are reported to use DJI's R SDK framing, not the Nano's. That document and demo fall under DJI's developer licence, which this project avoids so it can be Apache-2.0 and go to F-Droid. Each model would also need its own timing measured. The scan lists only the Nano, so the app never sends Nano commands to a camera that does not speak them.
- **Replacing Mimo's other features** (camera settings, live preview, fetching clips over Wi-Fi). Possible to study by capturing Mimo's own traffic on a phone you own, but not planned. Mimo stays the place for overlays.
- **Phone microphone to hear the start beep.** It sees only what the app already tracks over Bluetooth and would add a microphone permission to an app that promises not to leak anything.

## Release checklist

- [x] Licence Apache-2.0; `NOTICE` credits Osmosis and states the project is unofficial.
- [x] Safety wording in the README and `NOTICE`; no DJI SDK or Garmin SDK material in the tree.
- [ ] Fresh `git init`; keep `PLAN.md`, `footage/`, `*.fit`, `*.mp4` and trial logs out; search the tree for personal data before the first commit.
- [ ] Python tools in `tools/`: short README, no personal paths.
- [ ] Signed release APK; the release keystore backed up by the maintainer (losing it means no update can install over the release).
- [ ] F-Droid: reproducible build, free-software dependencies only (true now).
- [ ] Application id `io.github.levi2m.nanodash`, fixed once published.
