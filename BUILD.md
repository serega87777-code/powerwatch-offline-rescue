# Build instructions

## Public documentation only

If you only want to publish the repair notes, no build is required. The top-level markdown files and `index.html` are static documentation.

## Clean-room Android prototype

The Android prototype lives in:

```text
OpenPowerWatchSync/
```

It contains original code only. It does not include the old vendor APK, decompiled source, private MACs, or vendor assets.

### Option A: Android Studio

1. Install Android Studio.
2. Open the `OpenPowerWatchSync/` folder.
3. Let Android Studio install the requested SDK/build tools.
4. Build `app` / `debug`.

### Option B: command line

Install Android SDK commandline tools and Gradle, then create `OpenPowerWatchSync/local.properties`:

```properties
sdk.dir=/path/to/android-sdk
```

Run from `OpenPowerWatchSync/`:

```bash
gradle assembleDebug
```

## Current verification status

The prototype has been verified to:

- compile into a debug APK;
- generate the same framed BLE chunks for known-good commands `8421`, `8440`, and `810F050082`.

Real-device BLE testing of the clean-room prototype is still pending. The documentation includes a previously observed successful repair flow, but do not treat the prototype as a polished end-user release yet.
