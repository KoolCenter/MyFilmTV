# Update existing KoolCenter/MyFilmTV to v0.3

Upload changed files with folder paths preserved. Do not overwrite `sources.json` unless you intend to change your sources.

Updated/new Kotlin files:
- `app/src/main/java/com/example/myfilmtv/MainActivity.kt`
- `app/src/main/java/com/example/myfilmtv/Network.kt`
- `app/src/main/java/com/example/myfilmtv/PlayerActivity.kt`
- `app/src/main/java/com/example/myfilmtv/CatalogStore.kt` (new)
- `app/src/main/java/com/example/myfilmtv/CatalogLoader.kt` (new)

Updated Gradle:
- `app/build.gradle.kts` (version 0.3)

GitHub Actions runs after push to main. Download the artifact named `MyFilmTV-apk` (contains `MyFilmTV.apk`). Check APK version with `aapt dump badging` and expect `versionCode=3 versionName=0.3`.

A different debug signature may force an uninstall. For permanent upgrades configure a fixed, securely managed signing key.
