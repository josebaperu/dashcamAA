# Dashcam AA helper

Android Auto IoT controller for the Dashcam app. Record (also resumes) / Pause / Stop / Loop / Camera controls, plus a phone screen for testing without a car.

This is a personal sideloaded app. It is not a Play Store dashcam; the car UI is templated IoT controls, not a live camera preview (Android Auto does not allow that for third-party apps).

## Pairing

Both apps must be signed with the same key (the default debug keystore is enough). Install **Dashcam** first, open it, grant camera permission, then install this helper.

The helper binds to `com.aauto.dashcam` through the signature-protected action `com.aauto.dashcam.api.BIND`.

## Build

```bash
cd /home/super/dev/Aauto/dashcam_aa_helper
./gradlew assembleDebug
```

## Android Auto

1. Open Dashcam on the phone and grant permissions.
2. Connect the phone to the car (or Desktop Head Unit).
3. Developer settings: unknown sources / third-party apps, depending on the head unit.
4. Launch **Dashcam AA**. Grid buttons send the same commands as the phone UI.

The car can't start the camera by itself: Android doesn't let a background app open a camera service. If the status tile says **Open Dashcam**, open the Dashcam app on the phone and the car controls come back. **Waiting for camera** means recording is on but no frames are arriving (for example, another app is using the camera); the clock pauses until they resume.
