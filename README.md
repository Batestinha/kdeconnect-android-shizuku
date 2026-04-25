This release is based on current upstream KDE Connect Android 1.35.5 and builds on the earlier work from Shoukaku39’s kdeconnect-android-shizuku.

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