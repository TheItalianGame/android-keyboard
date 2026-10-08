# FUTO Android Keyboard Fork

This checkout is a Termux-buildable fork of FUTO Keyboard with local Android build shims and experimental keyboard-owned autofill work.

FUTO Keyboard is a privacy-focused Android keyboard forked from LatinIME. The upstream project is maintained by FUTO and remains licensed under the [FUTO Source First License 1.1](LICENSE.md). This fork keeps that offline-first direction while adding local development support and custom features.

Check out the [FUTO Keyboard website](https://keyboard.futo.tech/) for downloads and more information.

## What This Fork Adds

- Termux build support for Android without relying on a desktop Android Studio setup.
- A prebuilt native library path for builds that should not rebuild LatinIME native code.
- Keyboard-owned field autofill suggestions based on field names, hints, input type, and previously typed values.
- Local clipboard/sync experiments and supporting Android settings.
- Local model comparison and native build helper scripts used during development.

## Field Autofill

The field autofill feature learns complete values from recognized form fields and offers them as suggestion-row chips. It is keyboard-owned, local to the app data directory, and tap-to-fill only.

Recognized field types include:

- email
- phone
- first name
- last name
- full name
- username
- organization
- address
- city
- state or province
- ZIP or postal code

If you want to help translate the app, please do so via our Pontoon instance: https://i18n-keyboard.futo.org/

Due to custom license, pull requests to this repository require signing a [CLA](https://cla.futo.org/) which you can do after opening a PR. Contributions to the [layouts repo](https://github.com/futo-org/futo-keyboard-layouts) don't require CLA as they're Apache-2.0

Please do not submit AI-generated pull requests, as these tend to make the codebase more difficult to understand. Pull requests made with clear heavy use of AI may be closed without comment. Please also avoid using AI to write issues, and try to explain it in your own words instead.

The classifier uses Android `EditorInfo` metadata such as field name, hint text, label, input type, private IME options, and extras keys. Suggestions are ranked by exact field identity first, then by app/session context and usage.

Autofill is intentionally blocked for sensitive or unsuitable fields:

- password fields
- OTP, code, PIN, token, CVV/CVC, card, SSN-like fields
- URL fields
- fields marked with no personalized learning
- fields rejected by basic type validation

The feature is controlled by **Keyboard settings -> Field autofill** and also requires personalized suggestions to be enabled.

## Termux Build

Use the real repo under Termux-private storage:

```sh
cd /data/data/com.termux/files/home/Documents/android-keyboard
```

Do not build from:

```sh
/storage/emulated/0/Documents/android-keyboard
```

The shared-storage copy can break symlinks and is not build-safe.

This checkout uses local shims documented in [build.md](build.md). The important pieces are:

- `local.properties` points at `.android-sdk`
- `.android-sdk/build-tools/35.0.0/aapt2` is the Termux-compatible aapt2 wrapper
- `prebuilt-jni/arm64-v8a/libjni_latinime.so` is used when `-PtermuxPrebuiltNative` is passed

Reliable debug build:

```sh
mkdir -p logs
./gradlew \
  -PtermuxPrebuiltNative \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/home/Documents/android-keyboard/.android-sdk/build-tools/35.0.0/aapt2 \
  assembleUnstableDebug \
  > logs/build-unstable-debug.log 2>&1
```

Expected APK:

```sh
build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk
```

Copy and open installer:

```sh
cp build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk \
  /storage/emulated/0/Download/android-keyboard-unstable-debug.apk

termux-open /storage/emulated/0/Download/android-keyboard-unstable-debug.apk
```

## Development Notes

- Keep `.android-sdk/`, `.sdk-setup/`, `prebuilt-jni/`, `build-termux-native/`, and `local.properties` in place for local builds.
- Do not commit generated APKs, Gradle caches, build logs, or machine-specific SDK directories unless there is a specific reason.
- Use `build.md` as the source of truth for the Termux build setup.
- Build verification used for this fork:

```sh
./gradlew -PtermuxPrebuiltNative \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/home/Documents/android-keyboard/.android-sdk/build-tools/35.0.0/aapt2 \
  assembleUnstableDebug
```

## Upstream Project

Original upstream repository:

```text
https://github.com/futo-org/android-keyboard
```

FUTO Keyboard website:

```text
https://keyboard.futo.org/
```

For upstream contributions, follow the FUTO CLA and contribution rules from the original project.

Standard upstream builds can also be run with:

```sh
./gradlew assembleUnstableDebug
./gradlew assembleStableRelease
```

## APK signing

For official FUTO Keyboard versions, you can verify the APK's signing key fingerprint for integrity.

```
Signing key fingerprint for all versions except Google Play:

MD5: 3A:BB:71:C6:BB:E4:92:27:B1:E3:5D:81:01:48:6A:B0
SHA1: 5D:15:B3:6E:C9:6A:96:28:41:09:DD:62:93:0D:9C:39:9F:5F:06:43
SHA-256: 74:3F:AD:58:64:AB:C4:26:50:0B:2D:C2:C4:7C:8A:D3:24:CB:CD:16:03:3F:80:16:99:48:41:35:63:74:F9:95

```
