# PageAlert Live

PageAlert Live monitors multiple websites for keywords, missing text, or price drops and sounds a looping alarm until you disarm it. Each watchdog runs independently in the background with randomized check intervals and User-Agents, optional Wi-Fi/mobile and IPv4/IPv6 rotation, and notifications for HTTP errors. Watchdogs keep their creation order, and the app shows the last recorded running time if monitoring was interrupted.

[Get PageAlert Live on Google Play](https://play.google.com/store/apps/details?id=com.clinscout.pagealertlive) · [MIT License](LICENSE)

[Download the signed release APK](https://github.com/Grunwalskii/PageAlert-Live/releases/download/v1.0.20/PageAlert-Live-1.0.20.apk) (`1.0.20`; [SHA-256](https://github.com/Grunwalskii/PageAlert-Live/releases/download/v1.0.20/PageAlert-Live-1.0.20.apk.sha256)). This APK uses the same private upload key as GitHub versions 1.0.17–1.0.19 and can update them while retaining alerts and settings.

This GitHub APK cannot update the Play Store version: its upload signing certificate differs from Google Play's app signing certificate. Keep your existing app installed: version 1.0.11 has no export option and disables Android backup, and uninstalling deletes its local alerts and settings. Use a Play-signed update to preserve that data. The earlier debug APK also uses a different certificate.

Google Play app signing certificate SHA-256: `9B:C6:2C:60:4B:5C:F3:66:B7:9D:91:CF:1B:A6:A0:D4:BD:01:61:24:FD:E5:D3:51:B9:1D:5A:FF:26:CC:69:D1`.
