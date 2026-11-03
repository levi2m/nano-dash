# Releasing

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

2. Raise `versionCode` and `versionName` in `android/app/build.gradle.kts`.
3. From `android/`: `./gradlew :core:test :app:lintDebug :app:assembleRelease`. The signed APK is `app/build/outputs/apk/release/app-release.apk`; without `keystore.properties` it is `app-release-unsigned.apk`.
4. Check it: `apksigner verify --print-certs app-release.apk`, install it, and do a short ride.
5. Tag and publish: `git tag vX.Y.Z && git push origin vX.Y.Z`, then create a GitHub release and attach the APK and its SHA-256.
6. F-Droid: the build is plain Gradle with free-software dependencies only; submit the repository to the F-Droid data repository when a release is stable.
