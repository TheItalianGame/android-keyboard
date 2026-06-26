# Build in Termux

Use the real repo in Termux-private storage:

```sh
cd /data/data/com.termux/files/home/Documents/android-keyboard
```

Do not build from `/storage/emulated/0/Documents/android-keyboard`. The shared-storage copy can break symlinks and is not build-safe.

## Required Local Shims

This checkout has local Termux build shims:

- `local.properties` must point at this repo's SDK shim:

```properties
sdk.dir=/data/data/com.termux/files/home/Documents/android-keyboard/.android-sdk
```

- `.android-sdk/build-tools/35.0.0/aapt2` must be the local wrapper that runs `.sdk-setup/build-tools-r35/android-15/aapt2` through `grun`/`box64`.
- `prebuilt-jni/arm64-v8a/libjni_latinime.so` is the prebuilt native library.
- Pass `-PtermuxPrebuiltNative` so Gradle packages `prebuilt-jni` instead of trying to rebuild native code with CMake/NDK.

## Reliable Build Command

Use this exact command:

```sh
mkdir -p logs
./gradlew \
  -PtermuxPrebuiltNative \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/home/Documents/android-keyboard/.android-sdk/build-tools/35.0.0/aapt2 \
  assembleUnstableDebug \
  > logs/build-unstable-debug-fixed.log 2>&1
```

The `android.aapt2FromMavenOverride` property is required in Termux. Without it, Android Gradle Plugin tries to run its downloaded Linux `aapt2` and fails with `Syntax error: "(" unexpected`.

## APK Output

Expected APK:

```sh
build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk
```

Check it:

```sh
ls -lh build/outputs/apk/unstable/debug/*.apk
```

Copy it to Downloads:

```sh
cp build/outputs/apk/unstable/debug/android-keyboard-unstable-debug.apk \
  /storage/emulated/0/Download/android-keyboard-unstable-debug.apk
```

Open installer:

```sh
termux-open /storage/emulated/0/Download/android-keyboard-unstable-debug.apk
```

## If Build Fails

Inspect the log:

```sh
tail -n 160 logs/build-unstable-debug-fixed.log
```

Check required tools:

```sh
command -v aapt
command -v aapt2
command -v aidl
command -v apksigner
command -v zipalign
command -v java
.android-sdk/build-tools/35.0.0/aapt2 version
```

Do not delete these unless rebuilding the setup:

- `.android-sdk/`
- `.sdk-setup/`
- `prebuilt-jni/`
- `build-termux-native/`
- `local.properties`

`build-termux-native/` contains the native build output used to make `prebuilt-jni`.
