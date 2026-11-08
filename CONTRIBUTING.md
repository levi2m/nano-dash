# Contributing

The most useful contributions are real-world measurements and bug reports.

- **Other cameras or phones:** run a ride and open an issue with the ride folder's `meta.json` and `events.jsonl` (remove `gnss.csv` if you do not want to share your route; those two files hold no coordinates but do show the phone model, the exact ride times and the last five characters of the camera's address, and the in-app "All files" share also includes `gnss.csv`). Say the camera model, phone and Android version.
- **Accessibility:** a screen that reads badly with TalkBack, or a control that is hard to hit, is a bug; say which screen, your phone and the TalkBack version.
- **Code:** `core` is pure Kotlin with no Android dependency; keep it that way and add a test for new logic. Run `./gradlew :core:test :app:lintDebug :wear:lintDebug` from `android/` before sending a change; CI runs the same and builds both apps.
- **Dependencies:** Gradle checks each download against `android/gradle/verification-metadata.xml`. After changing a dependency or plugin version (Dependabot pull requests too, which fail until this is done), run from `android/`: `./gradlew --write-verification-metadata sha256 :core:test :app:lintDebug :wear:lintDebug :app:assembleDebug :app:assembleRelease :wear:assembleRelease`, look over the diff of that file and commit it. It records only the `aapt2` jar of the platform you build on; when the Android Gradle Plugin changes, add the other platforms' `aapt2` jars (linux, osx, windows) by hand, as the current file does.
- **Do not add** code or documents taken from DJI's SDK or developer material, or Garmin's FIT SDK: their licences do not allow it. Protocol facts must come from observation of your own camera or from MIT-licensed sources such as Osmosis.
- **Privacy:** no network access (neither app may declare the internet permission; `scripts/check-no-internet.sh` checks it in CI), no analytics, no crash reporters.
- **AI-assisted changes** follow [AGENTS.md](AGENTS.md), which lists the rules agents tend to miss.
- Licence: contributions are under Apache-2.0.
