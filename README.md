This fork is based on KDE Connect Android 1.35.14 (upstream master `707f2e76`) and builds on the earlier work from Shoukaku39’s kdeconnect-android-shizuku.

What changed in this fork relative to that earlier Shizuku work:

Rebased the Shizuku clipboard work onto current upstream KDE Connect.
Ported the integration to match upstream clipboard code after the Java-to-Kotlin migration.
Reworked clipboard monitoring around a Shizuku user service plus AIDL interface, instead of relying only on the older direct app-side approach.
When Android denies background clipboard access, the app now detects that event, asks the Shizuku-side service to perform the privileged clipboard read, and then delivers the clipboard text back to the main app.
Kept READ_LOGS support as a fallback path for devices where Shizuku is not available or not authorized, instead of removing it entirely.
Added the manifest, dependency, startup, and service wiring needed for the new Shizuku clipboard monitor.

In practical terms, this release preserves the original goal of the Shizuku fork, but updates it for modern upstream KDE Connect and restores working background clipboard sync without requiring READ_LOGS when Shizuku is present.

Credit to:

KDE upstream for the base project
Shoukaku39 for the original Shizuku-enabled fork this work builds on

## 1.35.14 update

Rebased the six fork commits onto KDE Connect upstream master `707f2e76`.
This includes notification listener recovery and resynchronization fixes,
media-session listener cleanup, the call-muting fix, and updated build dependencies.
The Shizuku clipboard integration and READ_LOGS fallback are retained.

## Build and download

With a compatible JDK and Android SDK 37 installed, run:

```sh
./gradlew --no-daemon --console=plain assembleDebug lintDebug testDebugUnitTest
```

The APK is generated at `build/outputs/apk/debug/kdeconnect-android-debug.apk`.

Download the APK and SHA-256 checksum from
[GitHub Releases](https://github.com/Batestinha/kdeconnect-android-shizuku/releases).
The published APK is the debug variant (`org.kde.kdeconnect_tp.debug`) and uses
the repository's existing debug signing key, matching the previous debug build.
