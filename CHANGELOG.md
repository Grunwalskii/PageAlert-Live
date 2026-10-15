# Changelog

## 1.0.11

- Changed the application ID and source namespace to `com.clinscout.pagealertlive` and renamed the internal application class and identifiers to PageAlert Live.
- Targets Android 16 / API 36 for the Play Store submission path.
- Added an offline Impressum in Settings with the supplied Clinscout Database GmbH address and email; the privacy policy now identifies the same provider/contact.
- This package installs separately from the earlier `dev.webalert` app. Existing watchdogs and Android notification/battery permissions do not transfer automatically.

## 1.0.2

- Renamed the app to PageAlert Live in the launcher, app header, settings, notifications, accessibility labels, and crawler identification.
- Preserved the package ID, preferences, notification channels, and signing certificate for installation over earlier WebAlert versions without losing monitor settings.
- Renamed the downloadable APK to `PageAlert-Live-debug.apk`.

## 1.0.1

- Disarming immediately rearms every monitor. The next matching check can sound a new alarm, including when the page continues to match.
- Stop monitoring & disarm stops the existing service directly, including after an alarm has already been disarmed. Stop commands no longer start foreground execution during shutdown.
- Late playback errors from a stopped audio player cannot release a newer alarm player. Audio-focus cleanup cannot abort shutdown.
- Monitoring notifications use normal priority and remain silent, making them eligible for the lock screen. The old default low-priority channel migrates on upgrade while explicit importance/blocking choices are retained.
- Monitoring and alarm notifications include public fallback content and controls that do not request authentication. Settings now include shortcuts for both notification categories and device notification preferences.
- Foreground entry uses the current command state and happens once per service session, preventing a stale alarm notification from overwriting monitoring status after disarm.
- Expanded notifications show one row per watchdog with a live countdown, plus checking, disabled, and alarm states.
- Removed the app subtitle and long settings descriptions, including the rearming explanation. The selected tab now has a filled accent background and selected accessibility state.
- Added tests for repeated matching alarms after disarm, the reported disarm-then-stop sequence, notification stopping, idle stop requests, channel migration, and lock-screen control metadata.

Install the updated APK over version 1.0.0 to retain monitor settings. Android's global and channel lock-screen notification preferences still control whether notifications appear.

## 1.0.0

Initial Android app with independent randomized website monitors, keyword and price triggers, foreground execution, looping alarms, notification controls, and persisted local settings.
