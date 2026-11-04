# Releasing

**Automatic:** once the three secrets below exist, every push to `main` that changes `android/` or the workflow and passes the tests publishes, from a separate `release` job that alone holds the signing secrets and a write token, a signed release `v<versionName>-<run number>` (the run number is also the `versionCode`). Add them in the repository's Settings → Secrets and variables → Actions, or with `gh secret set`:

- `KEYSTORE_B64`: the keystore from step 1, as base64 (`base64 -w0 nano-dash-release.jks`)
- `KEYSTORE_PASSWORD` and `KEY_PASSWORD`: its passwords (the key alias must be `nanodash`)

Without them CI only tests and builds. Steps 2–5 below are the manual route, still valid.

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

2. Raise `versionName` in `android/app/build.gradle.kts` if the version changed.
3. From `android/`: `./gradlew :core:test :app:lintDebug :app:assembleRelease -PversionCode=N`, with N higher than the latest release's number (the last part of its tag, e.g. 7 for `v0.1.0-7`); without it the code is 1 and the APK cannot install over a CI release. The signed APK is `app/build/outputs/apk/release/app-release.apk`; without `keystore.properties` it is `app-release-unsigned.apk`.
4. Check it: `apksigner verify --print-certs app-release.apk`, install it, and do a short ride.
5. Tag and publish: `git tag vX.Y.Z && git push origin vX.Y.Z`, then create a GitHub release and attach the APK and its SHA-256.
6. F-Droid: the build is plain Gradle with free-software dependencies only; submit the repository to the F-Droid data repository when a release is stable.
