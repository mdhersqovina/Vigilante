# Vigilant Android App Explanation

This document explains the five Java files in the kiosk Android application and how they work together.

## Overall Application Flow

1. Android finishes booting.
2. [`BootReceiver.java`](BootReceiver.java) opens [`MainActivity.java`](MainActivity.java).
3. `MainActivity` checks whether the device is registered.
4. An unregistered device opens [`RegistrationActivity.java`](RegistrationActivity.java).
5. A registered device displays the kiosk lock screen.
6. The user enters a six-digit unlock code.
7. The code is checked in Firebase Firestore.
8. If the code is valid, the station becomes active and the Android home screen opens.
9. [`KioskService.java`](KioskService.java) monitors the station remotely and locks it again when the session expires or the station status changes.
10. [`MyDeviceAdminReceiver.java`](MyDeviceAdminReceiver.java) supports Android device-owner and lock-task functionality.

## Firestore Structure Used by the App

The app uses these main Firestore locations:

```text
lounges/{loungeId}
stations/{androidDeviceId}
unlock_codes/{unlockCodeDocumentId}
```

The station document is identified by the device's Android ID.

## BootReceiver.java

`BootReceiver` is an Android `BroadcastReceiver`. It listens for the Android boot-completed broadcast:

```java
Intent.ACTION_BOOT_COMPLETED
```

When the device finishes booting, it starts `MainActivity`.

The Activity flags used by the receiver have these purposes:

- `FLAG_ACTIVITY_NEW_TASK`: required when starting an Activity from a receiver.
- `FLAG_ACTIVITY_CLEAR_TOP`: prevents duplicate Activities from building up in the stack.
- `FLAG_ACTIVITY_SINGLE_TOP`: reuses an existing Activity when possible.

### Purpose

This file automatically launches the kiosk application after the Android device restarts.

## KioskService.java

`KioskService` is a foreground Android service. It keeps monitoring and kiosk protection running while the main Activity is not visible.

### Main Responsibilities

- Connects to Firebase Firestore.
- Disables Firestore local caching for service checks.
- Gets the device's Android ID.
- Shows a persistent foreground notification.
- Checks whether an active session exists.
- Schedules a relock alarm.
- Listens for remote station changes.
- Relocks the device when a session expires or the station is remotely locked.

The service watches the station document:

```text
stations/{androidDeviceId}
```

### Startup Behavior

In `onCreate()`, the service:

1. Initializes Firestore.
2. Disables local persistence.
3. Retrieves the device ID.
4. Starts the foreground notification.
5. Starts listening for remote commands.
6. Checks the server for an active session.

### Session Recovery

`checkServerAndResumeTimer()` reads the station document and looks for values such as:

```text
status = Active
startTime
durationMinutes
prepaidDuration
duration
Duration
```

If the current time is before the calculated expiry time, the service schedules a relock. If the session has already expired, it calls `triggerRelock()` immediately.

### Duration Parsing

`parseDuration()` accepts several possible Firestore field names:

```text
durationMinutes
prepaidDuration
duration
Duration
```

It can handle numeric values, numeric strings, and time strings such as:

```text
10:30
```

The fallback duration in this service is 30 minutes.

### Relocking

`scheduleRelock()` uses Android's `AlarmManager` to reopen `MainActivity` at the expiry time. It passes a `FORCE_LOCK` extra so the Activity returns directly to kiosk mode.

`triggerRelock()` performs the same action immediately when a session is expired or a remote command requires locking.

### Remote Status Monitoring

The service relocks the station when its Firestore status becomes any of the following:

```text
Locked
Shutdown
Available
```

It also relocks the device if the station document is deleted.

### Service Restart Behavior

The service returns:

```java
START_STICKY
```

This asks Android to recreate the service if its process is killed.

## MainActivity.java

`MainActivity` is the main kiosk screen and the central controller for the application.

### Main Responsibilities

- Checks device registration.
- Displays station information.
- Collects the six-digit unlock PIN.
- Verifies unlock codes through Firestore.
- Starts and stops kiosk lock-task mode.
- Monitors network connectivity.
- Listens for remote station changes.
- Opens the Android home screen after a successful unlock.

### Registration and Session Check

`checkRegistrationAndSession()` reads:

```text
stations/{deviceId}
```

If the document does not exist, it opens `RegistrationActivity`.

If the document exists, `processActiveSession()` checks whether the station has a current active session. If the session is still valid, the app opens the Android home screen. Otherwise, it remains on the locked kiosk screen.

### Unlock Code Process

The user enters six digits. `submitCode()` combines the PIN fields and calls `verifyUnlockCode()`.

The code must match a Firestore document in:

```text
unlock_codes
```

The document must have:

```text
code = entered code
status = PENDING
```

The code must also belong to the current device through either:

```text
deviceId
```

or:

```text
stationId
```

### Unlocking

`performUnlock()` performs these actions:

1. Calculates the session expiry time.
2. Starts `KioskService`.
3. Changes the unlock code status to `ACTIVE`.
4. Updates the station status to `Active`.
5. Saves the session start time and duration.
6. Stops lock-task mode.
7. Opens the Android home screen.
8. Finishes the Activity.

### Kiosk Protection

`configureKiosk()` checks whether the app is the Android device owner.

When the app is the device owner, it can:

- Allow itself as a lock-task package.
- Disable the keyguard.
- Disable lock-task features on Android 9 and newer.
- Start lock-task mode while the station is locked.

Lock-task mode prevents the user from leaving the kiosk screen normally.

### Back Button Handling

When the station is locked, pressing Back displays:

```text
Device is locked
```

When the station is unlocked, the Activity can finish normally.

### Remote Updates

The Activity listens for updates to the station document and updates the displayed station name, hourly rate, and status.

When the status becomes `Available`, it forces the device back into kiosk mode. The foreground service handles additional statuses such as `Locked` and `Shutdown`.

### Connectivity Monitoring

`setupConnectivityMonitor()` listens for network availability changes. It updates:

- The online or offline text.
- The online or offline indicator color.
- The registration and session state when the network returns.

## MyDeviceAdminReceiver.java

`MyDeviceAdminReceiver` extends Android's:

```java
DeviceAdminReceiver
```

Its `onEnabled()` and `onDisabled()` methods currently only call the parent implementation and do not contain custom behavior.

### Purpose

The receiver supplies the component required by Android's `DevicePolicyManager`. `MainActivity` uses this component when checking device-owner status and configuring kiosk lock-task mode.

This receiver does not make the app a device owner by itself. Device-owner provisioning must happen separately through Android provisioning or ADB.

## RegistrationActivity.java

`RegistrationActivity` registers a new Android device with a lounge or station.

### User Inputs

- Lounge ID.
- Six-digit registration code.

The device ID is automatically retrieved from:

```java
Settings.Secure.ANDROID_ID
```

### Registration Process

`attemptRegistration()`:

1. Validates the lounge ID.
2. Validates that the registration code contains six digits.
3. Reads the lounge document:

   ```text
   lounges/{loungeId}
   ```

4. Passes the result to `verifyLoungeData()`.

### Verification

The lounge document must contain:

```text
registrationCode
regCodeExpiry
hourlyRate
```

The entered registration code must match the stored code and must not be expired.

### Station Creation

After successful verification, `createStationRecord()` creates:

```text
stations/{deviceId}
```

The new station contains fields such as:

```text
loungeId
name = New Station
status = Pending Setup
hourlyRate
deviceId
```

After creating the station record, the Activity opens `MainActivity`.

## Important Implementation Notes

### Client-Side Unlock Verification

Unlock-code verification happens in the Android client. Firebase Security Rules or a trusted backend should also enforce that:

- Codes cannot be reused.
- Codes can only be activated by the correct device.
- A user cannot modify station status or session fields without authorization.

Client-side checks alone are not a sufficient security boundary.

### Different Firestore Cache Behavior

`MainActivity` disables Firestore persistence, but `RegistrationActivity` does not. This means the two screens can have different caching behavior.

### Activity Setup Order

`MainActivity` calls `checkRegistrationAndSession()` before `setContentView()`. If a server request fails immediately, views such as `statusText` may still be `null`, so an error message may not be displayed.

### Repeated Duration Logic

`MainActivity` and `KioskService` contain similar duration parsing and Firestore monitoring logic. A shared helper could reduce duplication and prevent the two implementations from behaving differently over time.

### Device Owner Provisioning

`MyDeviceAdminReceiver` only provides the administrative receiver component. It does not provision the app as device owner. That step must be completed separately through Android provisioning or ADB.

### Dependence on Firestore Connectivity

The app depends heavily on Firestore connectivity for important operations. The service explicitly requests server data for critical checks, so offline behavior should be tested carefully.

## Short Summary

| File | Main Role |
| --- | --- |
| `BootReceiver.java` | Starts the kiosk app after Android boots. |
| `KioskService.java` | Monitors sessions and remotely relocks the device. |
| `MainActivity.java` | Displays the kiosk screen and handles unlocking. |
| `MyDeviceAdminReceiver.java` | Supports Android device-owner and kiosk APIs. |
| `RegistrationActivity.java` | Registers a new device with a lounge. |
