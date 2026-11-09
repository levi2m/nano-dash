# F-Droid

The F-Droid build is **reproducible**: F-Droid builds the tagged release from source, checks that the result is byte-for-byte the APK on the GitHub release, and then publishes that APK with our signature. So an F-Droid install and a GitHub install of the same version are the same file, and each can update the other. Only the phone app goes to F-Droid; the Wear OS app stays a GitHub download.

## What is ready

- **Listing:** `fastlane/metadata/android/en-US/` holds the title, short and full description and changelogs (named by version code); screenshots go in `images/phoneScreenshots/` once a fresh one is taken. The description says the Wear OS remote exists, is completely optional and is not part of the F-Droid build.
- **Recipe:** `fdroid/io.github.levi2m.nanodash.yml`, the file in fdroiddata as `metadata/io.github.levi2m.nanodash.yml`. It builds release `v0.1.0-58` (version code 58) in `android/app`. `Binaries` points at that release's phone APK and `AllowedAPKSigningKeys` names our signing certificate (`b728abd0...97e3b4f2`).
- **In the app:** when an F-Droid client installed the app, the Settings sheet hides "Check for updates", because the F-Droid client already updates it.
- **Licences:** the app is Apache-2.0. Its dependencies are AndroidX, Kotlin and Compose, all free software.

## Submission

- Fork: https://gitlab.com/levi2m/fdroiddata, branch `io.github.levi2m.nanodash`, with the recipe committed.
- The fork asks for GitLab's identity check before its pipeline runs. F-Droid says not to give GitLab a phone number or card: leave a note in the merge request and their team triggers the pipeline.
- Next: the merge request to `fdroid/fdroiddata` ("New app: Nano Dash"), then the reviewers.

## Adding a release later

Every push to `main` that touches `android/`, `scripts/` or the workflow makes a GitHub release, so F-Droid does not follow tags on its own (`UpdateCheckMode: None`). When a release is worth shipping on F-Droid:

1. Optional: add `fastlane/metadata/android/en-US/changelogs/<N>.txt` (500 bytes at most) before the release, so it is in the tagged commit. `N` is the CI run number that will build it, which cannot be known for sure in advance (pull-request runs use numbers too), so a missing changelog is the usual case and is fine.
2. In fdroiddata, add a `Builds` entry with `versionCode: N`, `commit:` the full hash of tag `v0.1.0-N` (fdroiddata asks for a hash, not a tag) and `versionCode=N` in `gradleprops`, and set `CurrentVersion` and `CurrentVersionCode`. `Binaries` needs no change: it is filled in from the version name and code.
3. Run `fdroid lint` and `fdroid build` (below). The build must end with "compared built binary to supplied reference binary successfully"; if it does not, the release is not reproducible and must not be submitted.

## Testing the recipe locally

In WSL or Linux, with the Android SDK and JDK 21 installed:

1. `pipx install fdroidserver`.
2. A sparse clone of fdroiddata with only its `config/` folder (lint needs its category list and icons): `git clone --depth 1 --filter=blob:none --sparse https://gitlab.com/fdroid/fdroiddata.git && git -C fdroiddata sparse-checkout set config`.
3. `mkdir -p fdroiddata/metadata` (the sparse checkout has no such folder), copy the recipe there without its comment lines, and add to `fdroiddata/config.yml`: `sdk_path:` (the SDK), `java_paths:` with `21:` (the JDK folder; F-Droid clears `JAVA_HOME`), and `gradle:` pointing at F-Droid's launcher, `gradlew.py` from https://gitlab.com/fdroid/gradlew-fdroid (the pip package does not include it, and F-Droid deletes `gradle-wrapper.jar`, so our wrapper cannot run).
4. Put the JDK's `bin` on `PATH`: the reproducibility check runs `apksigner`, which needs `java`, and fails with an empty error without it.
5. Clone the app into `fdroiddata/build/io.github.levi2m.nanodash` first (fdroidserver 2.4.5 reads its commit date before cloning).
6. In `fdroiddata/`: `fdroid rewritemeta io.github.levi2m.nanodash` (fdroiddata's CI rejects a file it would rewrite), `fdroid lint io.github.levi2m.nanodash`, then `fdroid build -v -l io.github.levi2m.nanodash`.

A local build skips the `sudo` step, so it uses the JDK already installed; F-Droid's build server runs it.

## Results (fdroidserver 2.4.5, release v0.1.0-58)

- `fdroid rewritemeta` leaves the recipe unchanged and `fdroid lint` passes.
- `fdroid build` builds `v0.1.0-58` (commit `143248c`) with Gradle 9.8.2 and JDK 21.0.13; the source scanner only removes `gradle-wrapper.jar`. It then downloads `nano-dash-v0.1.0-58.apk` from the GitHub release, copies its signature onto the rebuild and reports "compared built binary to supplied reference binary successfully" and "supplied reference binary has allowed signer b728abd0...". With the signature copied, the rebuild is byte-for-byte the GitHub APK.
- The APK is version code 58, version 0.1.0, with no internet permission. The watch app is not built.
- Not tested: F-Droid's build server itself, whose JDK 21 comes from Debian and may differ in build from the one used here. If its rebuild does not match, F-Droid will not publish that version.
- Release 58's tagged commit has no `changelogs/58.txt`, so that version shows no changelog in F-Droid.
