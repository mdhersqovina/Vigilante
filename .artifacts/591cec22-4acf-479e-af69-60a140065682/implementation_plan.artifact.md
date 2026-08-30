# Kiosk Mode with Timed Unlock

The goal is to implement a kiosk-style lock for Android TV. The app will lock the screen using Device Owner policies (Screen Pinning/Lock Task Mode). When a valid code is entered, the app will unlock (allowing access to the TV) for 60 seconds, after which it will automatically re-lock.

## Proposed Changes

### [Component Name] app

#### [MODIFY] [MainActivity.java](file:///C:/Users/Administrator/AndroidStudioProjects/kiosk/app/src/main/java/com/example/kiosk/MainActivity.java)
- Add a `Handler` and `Runnable` to manage the 60-second unlock timer.
- Update `stopKiosk()` to:
    - Stop the lock task mode.
    - Move the activity to the background so the user can access the TV launcher.
    - Post a delayed `Runnable` for 60 seconds.
- Implement the `relockRunnable` to:
    - Bring `MainActivity` to the foreground.
    - Re-enable lock task mode.
- Prevent bypassing the lock by overriding `onBackPressed` and ensuring `onResume` re-applies the lock if the timer hasn't started or has expired.

## Verification Plan

### Manual Verification
1. Deploy the app to the TV/Emulator.
2. Ensure the app is set as Device Owner (using ADB command).
3. Verify the screen is locked initially.
4. Enter the code `123456`.
5. Verify the app minimizes and the TV home screen is visible.
6. Wait for 60 seconds.
7. Verify the app automatically comes back to the foreground and re-locks the device.
