# Snapper for Android

##### Oh how I missed this

Android 14 reimplementation of Snapper 3.

Captures a selected screen region, keeps movable image pins above other apps, and stores a private history.

The optional LSPosed module that routes the physical screenshot chord into Snapper is built into this same APK; it is inert until it is enabled in LSPosed.

## Requirements

- JDK 17 or newer
- Android SDK with platform 37 and build-tools 36.0.0, found through `ANDROID_HOME` or `sdk.dir` in a `local.properties` file next to this README
- Gradle is fetched by the wrapper on first run

## Build

```sh
./gradlew :app:assembleDebug
```

The debug build is R8-minified and resource-shrunk. Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

It is signed with the standard Android debug key from `~/.android/debug.keystore`. To pin a specific debug key across machines, place it at `.tools/android-debug.keystore` (alias `androiddebugkey`, password `android`); the build picks it up automatically.

`./gradlew :app:assembleRelease` produces an unsigned release APK under `app/build/outputs/apk/release/`; sign it with your own key using `apksigner` before distributing.

## Install

```sh
adb install --no-incremental -r app/build/outputs/apk/debug/app-debug.apk
```

`--no-incremental` is required whenever the LSPosed module is used: an incremental install places `base.apk` on `incremental-fs`, which is unavailable when LSPosed loads the module during early `system_server` startup, so the hook is silently skipped.

## Physical screenshot button

The LSPosed hook ships inside the same APK; there is nothing separate to build. To turn it on:

1. Install the APK non-incrementally as shown above.
2. In LSPosed Manager, enable the Snapper module with scope **System Framework (`android`)** only.
3. Soft reboot, or reboot the phone.
4. In Snapper, open Settings and choose a *Screenshot buttons* mode (Native, Normal, Instant, or Freeze).

The main screen of the app walks through the same steps. The hook needs no root access or extra runtime permission. App updates that do not change the hook take effect without another reboot.

## Layout

```text
app/                          The app and its LSPosed hook
compile_only/xposed-api-stubs Compile-only stubs for the Xposed API (:xposed-stubs)
tools/provider-fixture        Sample external action provider used to test the
                              provider contract (:provider-fixture)
```

```sh
./gradlew :provider-fixture:assembleDebug
```
