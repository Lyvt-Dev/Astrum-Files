# Release instructions

1. Create an upload key (once, keep it out of the repository):
   ```bash
   keytool -genkeypair -v -keystore ~/keys/lyvt-release.jks -alias lyvt -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Give Gradle the signing values, using either `keystore.properties` in the project root (git-ignored):
   ```properties
   storeFile=/home/me/keys/lyvt-release.jks
   storePassword=...
   keyAlias=lyvt
   keyPassword=...
   ```
   or the environment variables `LYVT_KEYSTORE`, `LYVT_KEYSTORE_PASSWORD`, `LYVT_KEY_ALIAS` and `LYVT_KEY_PASSWORD`.
3. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
4. Build:
   ```bash
   ./gradlew :app:testDebugUnitTest :app:lintRelease :app:bundleRelease :app:assembleRelease
   ```
   The outputs are `app/build/outputs/bundle/release/app-release.aab` and `app/build/outputs/apk/release/`.
   Without signing values, the release APK is produced unsigned.
5. Before publishing:
   - Smoke-test on a physical device running Android 10, 13 and 15 (permissions differ).
   - Complete the Play Console "All files access" declaration, or ship a SAF-only flavour.
   - Fill in the Data safety form: no data collected, no data shared.
