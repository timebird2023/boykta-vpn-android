---
name: Android build setup on Replit
description: How the Boykta VPN Android project is built on Replit — env vars, SDK paths, known quirks.
---

# Android build setup on Replit

## Environment
- JDK 17 installed via `installSystemDependencies({ packages: ["jdk17"] })`
- JAVA_HOME: `/nix/store/xad649j61kwkh0id5wvyiab5rliprp4d-openjdk-17.0.15+6/lib/openjdk`
- Android SDK manually installed at `~/android-sdk/` (platforms/android-35, build-tools/35.0.1)
- Gradle wrapper JAR was missing from repo — downloaded from `https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar`

## Build command
```bash
export ANDROID_HOME=~/android-sdk
export ANDROID_SDK_ROOT=~/android-sdk
./gradlew assembleDebug -Pandroid.sdk.root=$ANDROID_HOME
```

## libXray.aar
Not present. The JNI `external` functions in `XrayManager.kt` fail at runtime only (not compile time). APK builds cleanly without it. Download and place in `app/libs/` to enable real VPN.

**Why:** External functions in Kotlin/JVM resolve via `System.loadLibrary()` at runtime — they don't cause linker errors at build time.

## Issues fixed during setup
- `allprojects { repositories {} }` in root `build.gradle` conflicts with `FAIL_ON_PROJECT_REPOS` in `settings.gradle` — removed the allprojects block
- `org.gradle.configuration-cache=true` conflicts with kapt — set to false
- Missing drawables: `ic_clock.xml`, `bg_ad_link_button.xml`
- Missing colors: `accent_dark`, `dialog_background`, `surface_variant`
- `android:gap` in LinearLayouts is API 35+ only — replaced with `android:layout_marginEnd/Bottom` on children
- `Theme.MaterialComponents.DayNight.Dialog.FullScreen` doesn't exist in Material 1.12.0 — use `Theme.MaterialComponents.DayNight.Dialog`
- `colorBackground` (without android: prefix) causes aapt2 namespace confusion — removed from theme, kept only `android:colorBackground`
- Missing mipmap launcher icons — generated programmatically with Python
- `statusDot` vs `dotStatus` ID mismatch in activity_main.xml — fixed constraint reference
- `isExpired` extension function in `com.boykta.vpn.model` not imported in MainActivity — added import
- `ConfigExportDialog.kt` nullable chain: `.trim().ifBlank` → `.trim()?.ifBlank ?: "/"`
