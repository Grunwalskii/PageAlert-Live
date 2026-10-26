# Changelog

## 1.0.18

- Add Battery details (Akkudetails) before the existing battery settings shortcut. Open Xiaomi/Poco/Redmi's per-app restrictions directly, with Android battery details and app settings as fallbacks on other phones.
- Move Network diagnostics after Device settings into a button that opens a dialog with the report, refresh, and copy controls.
- Add Duplicate to each watchdog. Open a prefilled new-watchdog editor and save an independent copy with a new ID; Cancel creates no extra watchdog. Keep the original configuration, history, and runtime state intact.

## 1.0.17

- Publish a release APK signed with the app's dedicated private upload key, replacing the debug-signed APK as the recommended GitHub download.
- The upload-signed APK cannot update a Play Store installation: Google Play uses a different app signing certificate. Keep the existing app installed and use a Play-signed update to preserve its local data.

## 1.0.16-dev

- Limit overview URLs to one line with an ellipsis for longer addresses. Editing and opening the page still use the full URL.

## 1.0.15-dev

- Label IP fallback explicitly as "Fallback to IPv4" or "Fallback to IPv6", keeping only the IP-family segment red. Normal checks retain their usual label.

## 1.0.14-dev

- Add Settings → Network diagnostics with the requested/used connection, original fallback exception and cause, outcome, timestamp, and Wi-Fi address/route/DNS details.
- Retain each watchdog's last IP fallback across later successful/mobile checks and app restarts. Diagnostics stay local and can be copied manually; no extra network probes or notifications are added.
- Explain that the red IP label marks fallback, include DNS answer counts for missing-family errors, and show the installed version in Settings.

## 1.0.13-dev

- Raise the HTML/text response limit from 2 MiB to 16 MiB, including for chunked and gzip-compressed responses.
- Keep compressed transfers and HTML-only checks: linked images, scripts, stylesheets, frames and media are not fetched by monitoring or price discovery.

## 1.0.12-dev

- Keep both configured Wi-Fi IP families in rotation, even when network detection or DNS temporarily reports only one. Retry the other family once after a connection failure, while preserving HTTP error and Retry-After handling.
- Show the actual IP family in red when fallback was needed, without an extra notification or warning line. Clear the mark after a check succeeds without fallback.
- Open this app's Battery details using current and older Android settings links, with this app's App info as the fallback.
- Publish the debug APK under `downloads/` for sideload testing, without creating a GitHub release or changing the existing Google Play release link.

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
