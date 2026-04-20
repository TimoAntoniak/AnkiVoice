# AnkiVoice

## Local TTS (Sherpa-ONNX + Piper)

- Settings → Voice: **System TTS** uses Android `TextToSpeech`; **Local Piper (exp)** runs **Sherpa-ONNX** + Piper VITS models fully on-device (offline after the first Gradle download of JNI + voice assets).

### Bundled Piper models (via Sherpa-ONNX releases)

Downloaded automatically on first build by `ensureSherpaPiperAssets` (wired to `preBuild`; needs network once):

| App language | Model package |
| --- | --- |
| English | `vits-piper-en_US-amy-low` |
| German | `vits-piper-de_DE-thorsten-medium` |

Native JNI (`libsherpa-onnx-jni.so`, ONNX Runtime) is pulled from the matching [sherpa-onnx Android release](https://github.com/k2-fsa/sherpa-onnx/releases). Generated paths are gitignored under `app/src/main/jniLibs/` and `app/src/main/assets/tts/`.

### License notes

- Piper voice packages include `MODEL_CARD` files with dataset terms; review before any store release.
- Sherpa-ONNX is Apache-2.0; upstream Kotlin API is vendored under `com.k2fsa.sherpa.onnx` as required by the JNI layer.

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

## TTS experiment measurements

This is the minimum checklist for go/no-go on local model integration.

1) Build/install and launch:

```bash
./gradlew :app:installDebug
adb shell am start -n dev.timoa.ankivoice/.MainActivity
```

2) Collect baseline memory while app is open:

```bash
adb shell dumpsys meminfo dev.timoa.ankivoice
```

3) Capture runtime TTS timings:

```bash
adb logcat | rg "AnkiVoiceTts|tts_first_audio|tts_done|tts_backend_selected"
```

The app now logs:
- `tts_backend_selected ...`
- `tts_first_audio ... latency_ms=...`
- `tts_done ... total_ms=... chars=...`

4) Capture app size impact:

```bash
ls -lh app/build/outputs/apk/debug/app-debug.apk
```

### Baseline results on connected device (Apr 20, 2026)

- Device detected via `adb`: `R5CX70WYF4J`
- Debug APK size: `11M` (`app/build/outputs/apk/debug/app-debug.apk`)
- App memory baseline (`dumpsys meminfo`):
  - `TOTAL PSS: 16439 KB`
  - `TOTAL RSS: 79664 KB`
  - `TOTAL SWAP PSS: 455 KB`

### Go / no-go rule for this branch

- Go: local backend achieves acceptable subjective quality and keeps `tts_first_audio` and `tts_done` within interactive limits on your target phone while avoiding major memory/app-size regressions.
- No-go: if quality gain is small or latency/memory cost is too high, keep `System TTS` as default and postpone full local runtime integration.