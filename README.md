# AnkiVoice

## Install on a USB-C connected phone (debug)

Prereqs:
- Android Studio platform tools (`adb`) installed
- Developer options + USB debugging enabled on the phone
- Phone connected by USB-C and authorized for this computer

### 1) Verify the phone is visible

```bash
adb devices
```

If it shows `unauthorized`, unlock the phone and accept the RSA prompt.

### 2) Build and install debug APK

From the project root:

```bash
./gradlew :app:installDebug
```

### 3) Launch the app from terminal (optional)

```bash
adb shell am start -n dev.timoa.ankivoice/.MainActivity
```

### 4) Useful debugging commands

```bash
# Stream app logs
adb logcat | rg "AnkiVoice|AndroidRuntime|StudyViewModel"

# Reinstall cleanly if install gets weird
adb uninstall dev.timoa.ankivoice
./gradlew :app:installDebug
```