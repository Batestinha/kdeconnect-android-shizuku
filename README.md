# KDE Connect Android with Shizuku clipboard sync

This unofficial fork is based on KDE Connect Android 1.35.14, including upstream
master commit `707f2e76`, and builds on Shoukaku39's earlier Shizuku clipboard work.

## What this fork adds to upstream

- Automatic background clipboard sync on Android 10+ through Shizuku. When Android
  denies the app a background clipboard read, a privileged Shizuku user service
  reads the clipboard and sends it back to KDE Connect.
- AIDL interfaces connecting the clipboard monitor service to the app, plus the
  Shizuku dependencies, provider, permission handling, and startup initialization.
- Integration with upstream's Kotlin clipboard listener, including propagation of
  Android's sensitive-clipboard flag so the existing privacy preference still applies.

The normal KDE Connect features are retained. The existing READ_LOGS clipboard
path remains available as a fallback when Shizuku is unavailable or unauthorized;
the app does not need READ_LOGS permission when the Shizuku service is used.

Compared with the earlier Shizuku fork, this implementation uses a dedicated
Shizuku user service and AIDL callbacks for privileged clipboard reads.

## 1.35.14 update

The fork patches are rebased onto upstream master `707f2e76`. This brings
notification listener recovery and resynchronization fixes, media-session listener
cleanup, the call-muting fix, and updated build dependencies.

## Downloads

The code and release artifacts are published on both mirrors:

- [Codeberg](https://codeberg.org/steadying/kdeconnect-android-shizuku/releases)
- [GitHub](https://github.com/Batestinha/kdeconnect-android-shizuku/releases)

## Build

With a compatible JDK and Android SDK 37 installed, run:

```sh
./gradlew --no-daemon --console=plain assembleDebug assembleRelease lintDebug testDebugUnitTest
```

The release APK is generated under `build/outputs/apk/release/` and uses
`org.kde.kdeconnect_tp`. It must be signed with the existing release key to update
previous release installations.

The debug APK is generated at `build/outputs/apk/debug/kdeconnect-android-debug.apk`.
It uses `org.kde.kdeconnect_tp.debug` and the repository's existing debug signing key.

## Credits

- KDE for upstream KDE Connect.
- Shoukaku39 for the original Shizuku-enabled fork.
