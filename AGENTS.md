# AGENTS.md — How to work with LiveCaption (for AI coding agents)

Purpose: provide focused, actionable knowledge an AI agent needs to be productive in this repository.

Checklist for an agent reading this project
- Identify the project type and build system (single-module Android app, Gradle Kotlin DSL).
- Understand where configuration and versions live (version catalog: `gradle/libs.versions.toml`).
- Know the quick-build/run/test commands for Windows PowerShell.
- Locate integration points (Vosk speech library, RECORD_AUDIO permission, asset model location).
- Follow code/IDE conventions (namespace, applicationId, Java 11 target).

Quick overview (the big picture)
- This is a single-module Android app (`:app`) using Jetpack Compose (Compose BOM present) and Vosk (`com.alphacephei:vosk-android`) for on-device speech recognition.
- Build is configured with the Gradle Kotlin DSL (`build.gradle.kts`) and a dependency version catalog (`gradle/libs.versions.toml`).
- Key IDs: namespace/applicationId = `com.livecaption` (see `app/build.gradle.kts` and `AndroidManifest.xml`).

Where to look (key files & folders)
- `app/build.gradle.kts` — module configuration (minSdk=26, targetSdk/compileSdk 36, Java 11, dependencies).
- `gradle/libs.versions.toml` — centralized dependency versions and plugin aliases (use `libs.xxx` in build scripts).
- `app/src/main/AndroidManifest.xml` — permissions (RECORD_AUDIO) and app metadata.
- `app/src/main/res/xml/` — `data_extraction_rules.xml`, `backup_rules.xml` (autobackup/data-extraction samples).
- `app/proguard-rules.pro` — release obfuscation rules (present, used by release buildType).
- `app/src/main/assets/` — model and other assets (currently empty; Vosk models are typically placed here).

Build / run / test (Windows PowerShell examples)
- Build debug APK:
```powershell
.\gradlew assembleDebug
```
- Install the debug APK to a connected device (after build):
```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```
- Run unit tests (host JVM):
```powershell
.\gradlew :app:testDebugUnitTest
```
- Run instrumented (device) tests:
```powershell
.\gradlew :app:connectedDebugAndroidTest
```
- Clean build caches:
```powershell
.\gradlew clean
```

Project-specific conventions and patterns
- Version catalog: All dependency versions are defined in `gradle/libs.versions.toml` and referenced via the `libs` catalog in `build.gradle.kts` (e.g. `implementation(libs.vosk.android)`). Modify versions there, not inline.
- Plugin usage: top-level `build.gradle.kts` references plugin aliases from the catalog (`alias(libs.plugins.android.application)`).
- compileSdk uses the Kotlin DSL `release(36) { minorApiLevel = 1 }` — when changing compile SDK, update both `build.gradle.kts` and the AGP version in the catalog.
- Java compatibility: project targets Java 11 via `compileOptions` in `app/build.gradle.kts`.

Integration and external dependencies
- Vosk (`com.alphacephei:vosk-android`) brings native/model requirements. Expect to place unpacked model files under `app/src/main/assets/<model>` and reference them from code. If you see crashes related to missing model files or UnsatisfiedLinkError, check `app/src/main/assets` and the APK contents (`.\gradlew assembleDebug && unzip -l <apk>`).
- Permissions: `RECORD_AUDIO` is requested in the manifest. Runtime permission handling must be implemented in app code (no code present in this scaffold).

Developer workflow notes
- The repository currently appears scaffolded: `app/src/main/java/com/livecaption/` is empty and `app/src/main/assets/` is empty. Expect work to add activity/composables and to bundle Vosk models into assets.
- Preferred developer workflow: open the project in Android Studio (recommended) for emulators, UI previews, and native debugging. CLI is supported for builds/tests as shown above.

Troubleshooting tips (concrete checks)
- If builds fail due to dependency resolution, ensure Gradle uses the project's `gradle/wrapper` by running `.\gradlew --version` and check `gradle/libs.versions.toml` for valid versions.
- If Vosk model not found at runtime, check APK assets:
```powershell
.\gradlew assembleDebug ; unzip -l app\build\outputs\apk\debug\app-debug.apk | Select-String "assets/"
```
- To find where to change package / namespace: `app/build.gradle.kts` (namespace) and `defaultConfig.applicationId`.

When editing code
- Keep changes localized: modify `app/` for application logic; keep build/version changes in `gradle/` and `build.gradle.kts` files.
- Follow the existing Kotlin/Gradle style (Kotlin DSL). Add dependencies via `libs` catalog entries and update `gradle/libs.versions.toml` accordingly.

If you are an AI code-writing agent
- Start by adding a minimal MainActivity / Compose entrypoint under `app/src/main/java/com/livecaption/` and a README describing how to supply Vosk models to `app/src/main/assets/`.
- Use the sample tests under `app/src/test` and `app/src/androidTest` as a template for package name and test structure (`package com.livecaption`).

End of guidance — add issues or PR notes to this file when you discover more project-specific patterns.

