# Adaptive Island

Adaptive Island is an Android application that recreates a Dynamic Island-style experience as a configurable, system-level overlay. It keeps a compact pill anchored near the device camera cutout and expands into live activity tiles when there is useful state to show.

The project goal is to provide a lightweight, device-aware island for Android devices whose system UI does not provide one, while keeping the feature set explicit and user-controlled. The app inspects the host device, exposes feature ownership and capability state, persists configuration locally, and runs the overlay only when an enabled overlay feature needs it.

## Current Status

This repository contains a working Android 2.0.0 implementation. The overlay currently renders charging, Bluetooth audio, media playback, and selected notifications. Calls, navigation, and delivery tracking are present in the control-center catalog as integration points, but their live data providers are not implemented in this version.

## Features

### Overlay

- Persistent black pill that can remain visible while enabled.
- Automatic placement at the top-center of the display and support for Android display cutouts.
- Compact state showing the primary live activity, an icon or glyph, and a relevant value such as battery percentage or notification count.
- Expanded state with one independent tile for each live activity.
- Animated expansion, collapse, tile entrance, charging glow, battery fill, and glass-style tile sheen.
- Tap the area below the compact pill to expand it.
- Tap outside the overlay to collapse it.
- Tap a notification or media tile to launch its source application when that application exposes a launch intent.
- Overlay runs in a foreground service and stops when no enabled overlay feature requires it.

### Live activities

| Feature | Behavior | Status |
| --- | --- | --- |
| Charging status | Shows charging state, plugged/full state, and battery percentage. Appears when the device is plugged in. | Implemented |
| Bluetooth audio | Shows the first connected A2DP or headset device and its overall accessory battery level when Android exposes one. | Implemented |
| Media playback | Shows the active transport notification's artwork, track title, artist, and playing/paused glyph. | Implemented |
| Notification activities | Shows up to three newest non-ongoing notifications with visible text, app icon, title, and message text. | Implemented |
| Call activity | Catalog entry for incoming and ongoing call awareness. | Data source not implemented |
| Navigation updates | Catalog entry for next turn, distance, and ETA. | Data source not implemented |
| Delivery tracking | Catalog entry for supported order and package notifications. | Data source not implemented |

Media and notification data is collected through the notification listener service. Ongoing notifications are excluded from the normal notification tiles, and transport notifications are promoted to the media activity.

### Feature control centre

- Device profile showing manufacturer, model, Android/API level, resolution, density, dp dimensions, smallest width, display cutout geometry, and overlay permission state.
- Master switch for the persistent black pill.
- Independent switches for each catalog feature.
- Feature grouping: Core island, System events, Media, calls and comms, and Live activities.
- Capability labels for supported, unsupported, or not-yet-tested features.
- Ownership selection for each feature:
  - **Adaptive Island**: this app is intended to render the feature.
  - **Dynamic Port**: the feature is intended to remain with Dynamic Port.
  - **Undecided**: no owner has been selected.
- Ownership is currently a persisted planning/configuration value; this repository does not contain a Dynamic Port integration.
- Real-time cutout alignment controls:
  - Top margin: 0-24 dp in the UI.
  - Pill height: 30-48 dp in the UI.
  - Idle width: 60-120 dp in the UI.
- Reset button for calibrated geometry defaults.
- Configuration survives app restarts through Android Preferences DataStore.

The calibrated defaults are a 4 dp top gap, 32 dp pill height, and 116 dp idle width. The shipped geometry is tuned for punch-hole devices such as the TECNO SPARK 50, but the controls are intended to accommodate other displays.

## Permissions and first run

The app requests or requires the following access depending on the features enabled:

- **Draw over other apps**: required for the floating island window. The app opens the system overlay permission page when the master overlay is enabled without access.
- **Notification listener access**: required for notification activities and media playback. The app opens Notification access settings when notification activities are enabled without access.
- **Post notifications** on Android 13/API 33 and newer: requested when the overlay service is enabled so its required foreground-service notification can be shown.
- **Bluetooth connect** on Android 12/API 31 and newer: requested when Bluetooth audio is enabled.
- **Foreground service, special-use foreground service**: declared for the overlay service that keeps the island available over other applications.

To use the overlay:

1. Launch Adaptive Island.
2. Enable **Persistent black pill**.
3. Grant overlay access in Android system settings.
4. Enable the live activities you want to display.
5. Grant notification listener or Bluetooth access when prompted by the selected feature.

The overlay service is not started until an enabled feature requiring an overlay is present and overlay permission has been granted.

## Requirements

- Android SDK with API 37 available for compilation.
- Android device or emulator running API 26 or newer.
- Java 17 for compilation.
- Gradle Wrapper included in the repository.

The package application ID is `dev.personal.adaptiveisland2`; the Kotlin namespace is `dev.personal.adaptiveisland`.

## Build and run

From the repository root on Windows:

```powershell
.\gradlew.bat assembleDebug
```

Install the debug APK on a connected device with:

```powershell
.\gradlew.bat installDebug
```

Build a release APK with:

```powershell
.\gradlew.bat assembleRelease
```

Release builds enable code shrinking and resource shrinking. No automated test source set is currently included; the primary verification path is a Gradle build followed by on-device permission and overlay checks.

## Technical overview

- **UI**: Kotlin and Jetpack Compose with Material 3.
- **Overlay**: `TYPE_APPLICATION_OVERLAY` hosted by `IslandOverlayService` as a non-focusable foreground service window.
- **State**: Compose `StateFlow`s fed by battery broadcasts, Bluetooth broadcasts, and the notification listener service.
- **Notifications/media**: `IslandNotificationListener` filters active notifications, caches app icons and labels, limits normal notification rendering to three tiles, and resolves media metadata from transport notifications and active media sessions.
- **Persistence**: Preferences DataStore stores feature enablement, ownership selections, and geometry values in the `adaptive_island_settings` store.
- **Device adaptation**: `DeviceProfileReader` reads display metrics, cutout insets, Android version, and overlay permission state for the control centre.
- **Animation and rendering**: Compose animation APIs render the compact pill, expanded activity stack, charging feedback, and translucent tile surfaces.

## Project layout

```text
app/src/main/java/dev/personal/adaptiveisland/
  activities/     Feature definitions and UI state models
  app/            Main activity, control centre, and view model
  capabilities/  Device and display profile detection
  integrations/  Battery, Bluetooth, notification, and media sources
  overlay/       Foreground service, touch root, island state, and rendering
  persistence/   Preferences DataStore access
```

## Known limitations

- Android exposes only one overall Bluetooth accessory battery value through the supported device APIs; per-earbud left/right values are not available here.
- Notification listener access is controlled by Android system settings and can vary by device manufacturer.
- The normal notification surface is intentionally limited to three visible-text notifications.
- Call, navigation, delivery, and Dynamic Port takeover behavior are cataloged but not wired to live providers.
- Overlay placement and cutout geometry can require calibration on devices with unusual status bars, camera cutouts, display densities, or manufacturer overlay restrictions.