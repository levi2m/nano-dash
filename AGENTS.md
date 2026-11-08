# Instructions for AI agents

Read this before changing anything. It holds the rules that are easy to break without noticing. [CONTRIBUTING.md](CONTRIBUTING.md) is the source for contribution rules; this file adds what an agent needs on top and does not replace it.

## What this is

Nano Dash: an Android ride logger that starts and stops a DJI Osmo Nano over Bluetooth, logs GPS and motion on the phone, and writes a `.fit` for DJI Mimo. Plus an optional Wear OS remote. Unofficial; not affiliated with DJI or Garmin.

## Layout

- `android/core`: pure Kotlin, **no Android dependency**. Camera protocol (`duml`, `camera`), FIT writer (`fit`), ride assembly (`ride`), watch protocol (`watch`). Tests in `core/src/test`, including a fake Nano.
- `android/app`: the phone app (service, UI, Bluetooth, watch listener).
- `android/wear`: the Wear OS watch app.
- `docs/`: design and measurement notes (`PROTOCOL.md`, `SYNC.md`, `FIT.md`, `WEAR_OS.md`, `WEAR_UI.md`, `GARMIN.md`, `RELEASING.md`, `FDROID.md`).
- `scripts/`: CI checks (`check-no-internet.sh`, `release-info.sh`).
- `tools/`: Python helpers (`validate_fit.py`) and spikes.
- `fdroid/`, `fastlane/`: F-Droid recipe and store listing.

## Checks

From `android/`, before every change you hand over:

```
./gradlew :core:test :app:lintDebug :wear:lintDebug
```

CI also builds both apps and runs `scripts/check-no-internet.sh`. Needs JDK 21 and Android SDK platform 37. If you cannot run the checks, say so; do not claim they pass.

## Hard rules

- **No network.** Neither app may declare `android.permission.INTERNET`, directly or through a library. No analytics, no crash reporters, no accounts.
- **`core` stays pure Kotlin.** No `android.*` imports there. New logic gets a test.
- **No DJI or Garmin SDK material.** No code, documents or demos from DJI's SDK or developer material, or Garmin's FIT SDK or Connect IQ SDK, in the tree. Protocol facts come from observing our own camera or from MIT-licensed sources such as Osmosis. Any future proprietary dependency goes only into a separate GitHub-only build flavour, never into the F-Droid build (see `docs/GARMIN.md`).
- **Free dependencies only** in the default build, so F-Droid can build it. No Google Play services.
- **No personal data** in commits: no GPS tracks, ride folders, `.fit` or video files, device addresses, serials or real names from logs.
- **Do not change** without the maintainer asking: the application id `io.github.levi2m.nanodash`, signing and release setup (`.github/workflows/ci.yml`, `docs/RELEASING.md`), the watch link's service id and protocol version in `WatchProtocol`, or the exported components (checked by `ManifestPolicyTest`).
- **Safety first.** A stray touch must not end a ride quietly (Stop on the watch is a hold). Never encourage using the phone while riding.

## Evidence, not guesses

- Timing, protocol and `.fit` changes say what was measured, on which camera, firmware and phone. Do not tune timings without a measurement.
- Mark untested work as untested, in code comments, docs and the PR. "Built" is not "tested on a real device".
- Facts about third-party platforms (Android rules, Garmin, Mimo) that you have not checked get labelled as unchecked.

## Accessibility

Every control at least 48 dp with a label; section titles are headings; status rows read as one phrase; follow the system theme and font size. See the accessibility notes in `README.md` and `docs/WEAR_UI.md`.

## Writing docs and messages

- Plain, short sentences. Say what is proven, what is not, and what is next.
- Keep `README.md`, `ROADMAP.md` and the relevant `docs/` file in step with the change.
- User-facing text is short; watch text is very short.

## Git and pull requests

- Work on a branch; never push to `main`. Keep each PR to one topic.
- Fill in `.github/pull_request_template.md`.
- Pushes to `main` that touch `android/`, `scripts/` or the workflow publish a signed release, so code merges are releases. Docs-only merges do not release.
- Do not add model names or identifiers to commits, code or docs.
