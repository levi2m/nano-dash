# Releasing

**Automatic:** once the three secrets below exist, every push to `main` that changes `android/`, `scripts/` or the workflow and passes the tests publishes a signed release `v<versionName>-<run number>` (the run number is also the `versionCode`; `versionName` is `nanodash.versionName` in `android/gradle.properties`). Add the secrets in the repository's Settings → Secrets and variables → Actions, or with `gh secret set`:

- `KEYSTORE_B64`: the keystore from step 1 of the manual route, as base64 (`base64 -w0 nano-dash-release.jks`)
- `KEYSTORE_PASSWORD` and `KEY_PASSWORD`: its passwords (the key alias must be `nanodash`)

Without them CI only runs the test job: `build` skips its steps and `publish` does not run. The manual route is at the end, still valid.

## What CI does

Three jobs in `.github/workflows/ci.yml`. No job keeps the checkout token, and the Gradle wrapper is validated before every build.

1. **test** (every push and pull request): `:core:test`, lint, and debug and release builds of both apps, then `scripts/check-no-internet.sh`, which fails if the merged manifests or the built release APKs of the phone or the watch app declare `android.permission.INTERNET`.
2. **build** (push to `main` only, after test): the only job that reads the signing secrets, with a read-only token and no write access. It builds the signed APKs, runs the same no-internet check on them, and fails if an APK is not validly signed. It uploads one short-lived artifact (kept one day) holding the APKs, their `.sha256` files and the release notes. It uses the `release` environment (see below).
3. **publish**: has `contents: write` and no secrets, and runs none of the project's code. It downloads the artifact, checks the `.sha256` files, creates the tag `v<versionName>-<run number>` on the built commit through the API, then runs `gh release create --verify-tag`, so a release cannot be made without that tag.

Each release carries four assets and the notes:

- `nano-dash-<tag>.apk` (phone) and `nano-dash-wear-<tag>.apk` (watch)
- `nano-dash-<tag>.apk.sha256` and `nano-dash-wear-<tag>.apk.sha256`, in `sha256sum` format: download next to the APK and run `sha256sum -c <file>.sha256`
- notes that begin with each APK's SHA-256 and the SHA-256 of the signing certificate (from `apksigner verify --print-certs`, from the SDK build-tools on the runner; the build fails if apksigner is missing), followed by GitHub's generated change list. The certificate fingerprint must be the same on every release; compare it with `apksigner verify --print-certs` on the APK you installed.

If the key secrets are missing, `build` skips its steps and `publish` does not run.

## Owner step: keep the signing secrets in the `release` environment

The `build` job names the environment `release`. GitHub creates it on the first run with no rules, and repository-level secrets still reach it, so nothing breaks today. But repository-level secrets can be read by a workflow on any branch of the repository. To close that:

1. Settings → Environments → `release` (it exists after the first run on `main`; or create it).
2. Under Deployment branches and tags, choose "Selected branches and tags" and add `main`. Optionally add Required reviewers (yourself), which makes every release wait for an approval click.
3. Add the three secrets (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`) as environment secrets of `release`.
4. Delete the repository-level copies of the three secrets.

Until step 4 is done, the secrets remain readable by workflows on other branches (SECURITY.md lists this).

## Manual route

1. **Once: make the release key** (keep it and its passwords outside the repository, and back it up; losing it means no update can ever install over a release):

   ```
   keytool -genkeypair -v -keystore nano-dash-release.jks -alias nanodash -keyalg RSA -keysize 4096 -validity 10000
   ```

   Create `android/keystore.properties` (git-ignored) next to `android/gradlew`:

   ```
   storeFile=/absolute/path/to/nano-dash-release.jks
   storePassword=...
   keyAlias=nanodash
   keyPassword=...
   ```

2. Raise `nanodash.versionName` in `android/gradle.properties` if the version changed (both apps, CI and the F-Droid recipe's `versionName` follow it).
3. From `android/`: `./gradlew :core:test :app:lintDebug :app:assembleRelease :wear:assembleRelease -PversionCode=N`, with N higher than the latest release's number (the last part of its tag, e.g. 7 for `v0.1.0-7`); without it the code is 1 and the APK cannot install over a CI release. The signed APK is `app/build/outputs/apk/release/app-release.apk` (the watch app: `wear/build/outputs/apk/release/wear-release.apk`); without `keystore.properties` it is `app-release-unsigned.apk`.
4. Check it: `apksigner verify --print-certs app-release.apk`, install it, and do a short ride.
5. Tag and publish: `git tag vX.Y.Z && git push origin vX.Y.Z`, then create a GitHub release and attach the APK and its SHA-256 (`sha256sum app-release.apk > nano-dash-vX.Y.Z.apk.sha256`, after renaming the APK to the name inside the file). CI does this by itself.
6. F-Droid: the build is plain Gradle with free-software dependencies only. The recipe is not filed yet; see [FDROID.md](FDROID.md).
