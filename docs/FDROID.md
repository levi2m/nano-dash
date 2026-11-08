# F-Droid

F-Droid builds the app from source and signs it with its own key, so an F-Droid install and a GitHub install cannot update over each other. Pick one source per phone.

## What is ready in this repository

- **Listing:** `fastlane/metadata/android/en-US/` holds the title, short and full description and a changelog (named by version code); screenshots go in `images/phoneScreenshots/` once a fresh one is taken. F-Droid reads them from here. The description says the Wear OS remote exists, is completely optional and is not part of the F-Droid build.
- **Recipe:** `fdroid/io.github.levi2m.nanodash.yml`, to be copied into the F-Droid data repository. It builds only the phone app (`subdir: android/app`); the watch APK stays a GitHub-only download. It builds version code 48 from commit `295eadf`, which carries `changelogs/48.txt`. No GitHub release has number 48 (that CI run checked a pull request); that is harmless, as F-Droid signs with its own key. The merge request has not been filed.
- **In the app:** when an F-Droid client installed the app, the Settings sheet hides "Check for updates", because F-Droid updates the app itself and a GitHub APK is signed with another key.
- **Source checks that were run** (see the results at the end of this file): a fresh public clone builds the release APK with no signing key, the two builds can be compared, and the dependency list has no Google Play services, analytics or crash reporters.
- **Licences:** the app is Apache-2.0. Its dependencies are AndroidX, Kotlin and Compose, all free software.

## What only the maintainer can do

1. Create an account on gitlab.com and fork `fdroid/fdroiddata`.
2. Copy `fdroid/io.github.levi2m.nanodash.yml` to `metadata/io.github.levi2m.nanodash.yml` in the fork.
3. Test it with F-Droid's own tools (`fdroidserver`, a build server or the Docker image they document): `fdroid lint io.github.levi2m.nanodash`, then `fdroid build -v -l io.github.levi2m.nanodash`. The points most likely to need a fix are `subdir`, where the Gradle wrapper is found, and getting a JDK 21 in the `sudo` step.
4. Open a merge request and answer the reviewers. Their scanner checks the APK and the dependencies, and they may ask for changes to the recipe.

## Updating later

The recipe lists builds by hand. Every push to `main` that touches `android/`, `scripts/` or the workflow creates a GitHub release, so tags are not a signal for F-Droid. A docs-only push (`fastlane/`, `fdroid/`, `docs/`) makes no CI run and no release, and pull-request runs use up run numbers too, so the next run number cannot be predicted. That is why the recipe points at a commit hash, not a tag:

1. Add `fastlane/metadata/android/en-US/changelogs/<N>.txt` (500 bytes at most) on `main`, with `N` higher than the last F-Droid version code and than any GitHub build a phone might have.
2. Add a `Builds` entry with `versionCode: N`, `commit:` the full hash of a commit that has that changelog, and `versionCode=N` in `gradleprops`; set `CurrentVersion` and `CurrentVersionCode`.
3. Run `fdroid lint` and `fdroid build` (below) before sending the change to fdroiddata.

Whether F-Droid reads the listing from the built commit or from the default branch has not been checked.

## Testing the recipe locally

In WSL or Linux, with the Android SDK and JDK 21 installed:

1. `pipx install fdroidserver`.
2. A sparse clone of fdroiddata with only its `config/` folder (lint needs its category list and icons): `git clone --depth 1 --filter=blob:none --sparse https://gitlab.com/fdroid/fdroiddata.git && git -C fdroiddata sparse-checkout set config`.
3. Copy the recipe to `fdroiddata/metadata/`, and add to `fdroiddata/config.yml`: `sdk_path:` (the SDK), `java_paths:` with `21:` (the JDK folder; F-Droid clears `JAVA_HOME`), and `gradle:` pointing at F-Droid's launcher, `gradlew.py` from https://gitlab.com/fdroid/gradlew-fdroid (the pip package does not include it, and F-Droid deletes `gradle-wrapper.jar`, so our wrapper cannot run).
4. Clone the app into `fdroiddata/build/io.github.levi2m.nanodash` first (fdroidserver 2.4.5 reads its commit date before cloning).
5. In `fdroiddata/`: `fdroid lint io.github.levi2m.nanodash`, then `fdroid build -v -l io.github.levi2m.nanodash`.

A local build skips the `sudo` step, so it uses the JDK already installed; F-Droid's build server runs it.

## Results of the recipe test (version code 48, commit 295eadf)

- `fdroid lint` passes (fdroidserver 2.4.5, categories from fdroiddata).
- `fdroid build` succeeds: the source scanner only removes `gradle-wrapper.jar`, as it always does, and Gradle 9.8.2 with JDK 21 builds `io.github.levi2m.nanodash_48.apk` (1.1 MB, unsigned), version code 48, version 0.1.0, with no internet permission. The watch app is not built.
- Not tested: the `sudo` step that installs JDK 21 on F-Droid's build server, and F-Droid's reproducibility check against our signed APK (not used: F-Droid signs with its own key).

## Results of the source checks (commit 4aa1a34, version code 34)

- A fresh public clone builds `assembleRelease` with no signing key and no local files: it succeeds and gives `app-release-unsigned.apk` (1.09 MB).
- Reproducible on one machine: two clones in different folders, with Gradle's build cache off and every task re-run, produced a byte-for-byte identical unsigned APK (SHA-256 `38f6a974...dad4672`). F-Droid's build server has a different JDK build, so this is a good sign, not a guarantee.
- The release runtime dependency list contains no Google Play services, Firebase, Crashlytics, ad, analytics or tracker libraries.
