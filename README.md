# NeoSteam Android

NeoSteam for Android is an ARM64 preview of the local practice client and content bootstrapper. The release page provides the signed APK and its SHA-256 checksum.

The app requires Android 7.0 or later on a device with the arm64-v8a ABI. To update, install the newer signed APK over the existing app.

- Android 8 or later can ask you to allow installation from the browser or file manager that opens the APK. On Android 7, use the Unknown sources setting in Security.
- If Android blocks installation because the developer is unverified, follow [Google's advanced flow](https://support.google.com/android/answer/17588095) or install the verified APK with Android SDK Platform Tools using `adb install` and `adb install -r` for updates.
- Compare the APK's SHA-256 to the checksum for the same release before installing.

Live world entry requires the Android server session adapter.
