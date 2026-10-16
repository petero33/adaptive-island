# Adaptive Island 2.0 — Complete Technical Documentation

This document is the full, ground-level description of the Adaptive Island app:
what it is, how it is built, every module, every feature, every animation, every
known bug, the resource measurements taken on a real device, and what is still
missing. It is written to be read top-to-bottom but each section stands alone.

It describes the repository at commit `9632a26` plus the uncommitted working-tree
changes present at the time of writing.

---

## 1. What this app is

Adaptive Island recreates an Apple-style "Dynamic Island" on Android. It keeps a
small black pill pinned to the top-center of the screen (over the camera punch
hole) and grows it into a stack of live-activity cards — charging, Bluetooth,
media, and notifications. It is a personal, non-root, overlay-window app: it does
not modify the system UI, it draws its own window on top of everything.

The app has two faces:

1. **The island overlay** — a foreground service that owns a floating window and
   renders the pill/tiles with Jetpack Compose.
2. **The Feature Control Centre** — a normal activity that shows the device
   profile, lets the user enable/disable each feature, choose "ownership"
   (Adaptive Island vs Dynamic Port vs Undecided), calibrate geometry, and read a
   resource-cost diagnostics card.

The stated project goal (README): provide a lightweight, device-aware island for
Android devices whose system UI does not ship one, with an explicit,
user-controlled feature set, host-device introspection, local-only persistence,
and a service that runs only when an enabled feature needs it.

---

## 2. Project identity and build configuration

| Item | Value |
| --- | --- |
| Repository | `https://github.com/petero33/adaptive-island.git` (branch `main`) |
| applicationId | `dev.personal.adaptiveisland2` |
| Kotlin namespace | `dev.personal.adaptiveisland` |
| versionName / versionCode | `2.0.0` / `1` |
| compileSdk / buildTools | `37` / `36.0.0` |
| targetSdk / minSdk | `36` / `26` |
| Java / Kotlin | JDK 17 / Kotlin 2.4.21 |
| Gradle / AGP | 9.8.1 / 9.4.1 (AGP 9 line) |
| Compose BOM | `2026.09.00` |
| DataStore | `1.2.1` |

Single Gradle module `:app`. No test source set exists (no unit or instrumentation
tests). Release builds enable `isMinifyEnabled` and `isShrinkResources` with the
default optimized ProGuard rules plus `proguard-rules.pro`.

Dependencies (from `gradle/libs.versions.toml`):
- `androidx.core:core-ktx`
- `androidx.lifecycle:lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose`
- `androidx.activity:activity-compose`
- Compose: `ui`, `ui-graphics`, `animation`, `material3`, tooling/preview
- `androidx.datastore:datastore-preferences`

Notably absent: no Hilt/DI, no Room, no navigation library, no Accompanist, no
coroutines-test. Everything is hand-wired singletons and DataStore.

---

## 3. Repository layout

```text
app/src/main/java/dev/personal/adaptiveisland/
  activities/     FeatureCatalog.kt, FeatureUiState.kt      (feature definitions + UI state model)
  app/            MainActivity.kt, AdaptiveIslandApp.kt,
                  FeatureControlScreen.kt, FeatureControlViewModel.kt,
                  theme/Theme.kt                            (control centre + app entry)
  capabilities/   DeviceProfile.kt                          (device + cutout + permission probe)
  diagnostics/    (EMPTY - placeholder)
  integrations/   BatterySource.kt, BluetoothSource.kt,
                  NotificationSource.kt                     (contains MediaSource + listener)
  lifecycle/      (EMPTY - placeholder)
  overlay/        IslandOverlayService.kt, IslandContent.kt,
                  IslandActivity.kt, IslandGeometry.kt,
                  IslandExpansion.kt, IslandSelection.kt,
                  ActivityPriority.kt, OverlayTouchRoot.kt   (the island itself)
  ownership/      (EMPTY - placeholder)
  persistence/    FeaturePreferences.kt                     (DataStore)
```

There are 20 Kotlin files. The `diagnostics`, `lifecycle`, and `ownership`
directories are empty scaffolding (left over from an earlier package plan).

Manifest declares:
- Permissions: `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `BLUETOOTH`
  (maxSdk 30), `BLUETOOTH_CONNECT`.
- `.app.MainActivity` (launcher, exported).
- `.integrations.IslandNotificationListener` (bound, exported, permission
  `BIND_NOTIFICATION_LISTENER_SERVICE`).
- `.overlay.IslandOverlayService` (not exported, `foregroundServiceType="specialUse"`).
- No `android:process` anywhere → **everything runs in one process**, which is why
  the process-wide singletons (`NotificationSource`, `MediaSource`,
  `IslandExpansion`, `IslandSelection`) can be shared between the activity, the
  listener, and the overlay service.

---

## 4. High-level architecture

```
                         ┌───────────────────────────────────────────┐
                         │              ONE PROCESS                    │
                         │                                             │
  user taps app icon ───►│  MainActivity (ComponentActivity)           │
                         │     └─ setContent { AdaptiveIslandApp }     │
                         │           └─ FeatureControlScreen           │
                         │                 ▲                           │
                         │           FeatureControlViewModel           │
                         │                 │ reads/writes              │
                         │                 ▼                           │
                         │        FeaturePreferences (DataStore)       │
                         │                 │ flows                     │
                         │                 ▼                           │
                         │     viewModel.overlayShouldRun             │
                         │        (enabled && requiresOverlay          │
                         │                 && overlayGranted)          │
                         │                 │                           │
                         │                 ▼ start/stop                │
                         │  IslandOverlayService (FGS, specialUse)     │
                         │     └─ WindowManager.addView(root, params)  │
                         │           └─ OverlayTouchRoot               │
                         │                 └─ ComposeView              │
                         │                       └─ IslandHost(state)  │
                         │                             ▲               │
                         │                 rememberIslandState()       │
                         │                             ▲               │
                         │        ┌────────────────────┼──────────────┐│
                         │        │                    │              ││
                         │  BatterySource     BluetoothSource    NotificationSource ││
                         │  (sticky bcast)    (bcast + reflect)   ▲    MediaSource   ││
                         │                                        │     ▲            │
                         │                    IslandNotificationListener ─┘           │
                         │                       (NotificationListenerService)        │
                         └───────────────────────────────────────────┘
```

Key architectural decisions:

- **One process, shared singletons.** State that must survive config changes and
  be visible to a non-Activity window (the overlay) is held in `object`
  singletons backed by `MutableStateFlow`.
- **Compose inside a Service.** `IslandOverlayService` implements
  `LifecycleOwner`, `ViewModelStoreOwner`, and `SavedStateRegistryOwner` by hand
  (a `LifecycleRegistry`, a `ViewModelStore`, and a
  `SavedStateRegistryController`), then attaches those to the `ComposeView` via
  `setViewTreeLifecycleOwner` / `setViewTreeViewModelStoreOwner` /
  `setViewTreeSavedStateRegistryOwner`. This is the standard trick for hosting
  Compose outside an Activity.
- **Preferences drive everything.** Every feature enable flag, ownership choice,
  and geometry value is a separate DataStore key. The overlay recomposes from
  those flows.
- **Push, not poll.** Battery is a sticky broadcast; Bluetooth is a broadcast
  receiver plus a reflective battery read; notifications come from the listener
  callback (debounced 90 ms). No `Handler`/`Timer` polling loops exist for data.

---

## 5. Runtime lifecycle

### 5.1 App launch / control centre

`MainActivity.onCreate` → `enableEdgeToEdge()` → reads the device profile →
`setContent { AdaptiveIslandTheme { AdaptiveIslandApp(profile, viewModel) } }`.
It posts a `decorView.post { refreshProfile(withCutout = true) }` so the display
cutout can be read once the window is attached. It then collects
`viewModel.overlayShouldRun` under `repeatOnLifecycle(STARTED)` and calls
`syncOverlayService()` on every emission.

`onResume` refreshes the profile, refreshes the overlay-permission snapshot,
syncs the service, and then attempts the three "ask once" flows:
- `maybeRequestNotificationPermission()` (API 33+, only if the overlay should run),
- `maybeRequestBluetoothPermission()` (API 31+, only if Bluetooth feature is on),
- `maybeRequestNotificationAccess()` (opens `ACTION_NOTIFICATION_LISTENER_SETTINGS`
  once if the notifications feature is on and listener access is missing).

`syncOverlayService()` starts `IslandOverlayService` when
`overlayShouldRun && Settings.canDrawOverlays()`, otherwise stops it.

### 5.2 Overlay service

`IslandOverlayService.onCreate`:
1. restores saved state, drives lifecycle to ON_CREATE,
2. gets `WindowManager`,
3. `startInForeground()` (creates the `IMPORTANCE_MIN` channel and calls
   `ServiceCompat.startForeground` with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on
   API 34+),
4. drives lifecycle to ON_START/ON_RESUME,
5. launches a coroutine collecting `topGapDpFlow()` to live-reposition the window
   when the top-gap preference changes (calls `updateViewLayout`).

`onStartCommand` re-checks overlay permission (stops itself if revoked), then
`showIsland()` (idempotent). Returns `START_STICKY`.

`onDestroy` cancels the scope, removes the view, drives lifecycle to DESTROY,
clears the ViewModelStore.

### 5.3 Notification listener

`IslandNotificationListener.onListenerConnected` schedules an immediate refresh.
`onNotificationPosted` / `onNotificationRemoved` schedule a debounced refresh
(90 ms, cancelling any pending job). `refresh()` reads `activeNotifications`,
then `publishMedia(all)` and `publishNotifications(all)`.

---

## 6. Data model

### 6.1 `IslandActivity` (sealed interface)

The single abstraction for anything the island can show. Four variants:

- `Charging(level, charging, full)` — id `"charging"`
- `Bluetooth(deviceName, batteryPercent)` — id `"bluetooth"`
- `Notification(key, packageName, appLabel, title, text, icon, category,
  hasActions, hasReply)` — id `"notification:$key"`
- `Media(packageName, appLabel, title, artist, icon, isPlaying)` — id `"media"`

### 6.2 `IslandState`

The immutable snapshot handed to the UI. Fields: `showIdlePill`, `activities`,
`topGapDp`, `pillHeightDp`, `idleWidthDp`, `prioritized`, `stackEnabled`,
`quickActionsEnabled`, `reduceEffects`, `lowPower`. Derived: `charging` (first
Charging), `notificationCount`, `primary` (first activity), `hasContent`.

### 6.3 Source DTOs

- `BatteryState(levelPercent, plugged, charging, full)`
- `BluetoothState(connected, deviceName, batteryPercent)`
- `NotificationItem(key, packageName, appLabel, title, text, postedAt, icon,
  category, hasActions, hasReply, sensitive)`
- `MediaItem(packageName, appLabel, title, artist, icon, isPlaying, postedAt)`
- `QuickAction(title, requiresText)`

### 6.4 Feature model

`FeatureDefinition` (static) + `FeatureUiState` (definition + enabled +
ownership + capability + permission). Enums: `Capability {SUPPORTED, UNSUPPORTED,
UNKNOWN}`, `Ownership {ADAPTIVE_ISLAND, DYNAMIC_PORT, UNDECIDED}`,
`PermissionState {NOT_REQUIRED, GRANTED, DENIED}`, `FeatureCost {LOW, MEDIUM,
HIGH}`, `FeatureGroup {CORE, SYSTEM, MEDIA, LIVE, PRIVACY}`.

---

## 7. Data sources

### 7.1 `BatterySource`

A `callbackFlow` over the sticky `ACTION_BATTERY_CHANGED` broadcast, registered
with `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`. It emits the
sticky value immediately on subscription, then on every change. `distinctUntilChanged().conflate()`.

`charging = status == BATTERY_STATUS_CHARGING`; `full = status ==
BATTERY_STATUS_FULL`; `plugged = EXTRA_PLUGGED != 0`. This `charging` flag drives
the charging-glow animation (see the bug in §14.2).

### 7.2 `BluetoothSource`

A `callbackFlow` over `ACTION_ACL_CONNECTED`, `ACTION_ACL_DISCONNECTED`,
`ACTION_BOND_STATE_CHANGED`, `ACTION_NAME_CHANGED`, `ACTION_STATE_CHANGED`.
`current()` walks `getConnectedDevices(A2DP)` then `(HEADSET)` and returns the
first bonded (or any) device. Battery is read by **reflection** on the hidden
`BluetoothDevice#getBatteryLevel()` (API 31+), clamped to 0..100, `null` on any
failure. Requires `BLUETOOTH_CONNECT` on API 31+; returns empty state without it.
Only one overall accessory battery value is available — per-bud L/R is not
exposed by Android.

### 7.3 `NotificationSource` + `IslandNotificationListener`

The listener's `publishNotifications` pipeline:
1. take `activeNotifications`,
2. drop ongoing notifications,
3. drop `CATEGORY_TRANSPORT` (those become media),
4. require visible title/text,
5. **group by package, keep the single most important per app** (rank, then
   newest post time) — the "one notification per app" rule,
6. sort by rank desc, then post time desc,
7. take `MAX_NOTIFICATION_TILES` (=3),
8. map to `NotificationItem`, registering live actions as a side effect.

`rankOf` delegates to `ActivityPriority.rankNotification(category, hasActions)`.

`toItem` extracts title/text (EXTRA_TITLE, EXTRA_TEXT / EXTRA_BIG_TEXT /
EXTRA_SUMMARY_TEXT), rasterises `largeIcon ?: appIcon`, and registers the
notification's actions (`RegisteredAction(label, intent: PendingIntent,
remoteInputs)`) into the process-wide `registry` keyed by notification key, along
with its `contentIntent` (the "open" PI). `sensitive = visibility ==
VISIBILITY_SECRET`.

Caching: `MediaCache` keeps an `LruCache<String,Bitmap>(16)` of icons (each
rasterised to 96×96 ARGB_8888 ≈ 36 KB) and an `LruCache<String,String>(32)` of
app labels. Icons are always rendered to a fixed pixel size because adaptive icons
report an intrinsic size of −1 and would otherwise produce a 1×1 bitmap.

`NotificationSource` (the `object`) exposes `notifications: StateFlow`, plus:
- `actionsFor(key)` → `List<QuickAction>` for chip rendering,
- `fireAction(context, key, index, text=null)` → sends the action `PendingIntent`,
- `open(key)` → sends the content `PendingIntent`,
- `publish(items)` → prunes the registry to live keys and updates the StateFlow
  (reference-equality guard prevents identical refreshes from recomposing).

### 7.4 `MediaSource`

Also defined in `NotificationSource.kt`. Holds the current `MediaItem?` and the
`MediaController?`. `attach(item, controller)` swaps the controller and updates
the StateFlow. Transport methods: `play()`, `pause()`, `toggle()`, `next()`,
`previous()` — all delegate to `MediaController.transportControls`.

`publishMedia` finds the (first) `CATEGORY_TRANSPORT` notification, resolves its
`MediaController` via `MediaSessionManager.getActiveSessions(component)` matched
on the session token, reads title/artist/artwork from extras or session metadata,
and determines playing state (`playbackState == PLAYING` or the "Pause/Play"
action heuristic `isPlaying()`).

**Dead code note:** `toMediaCompat`, `toMedia` are duplicates/unused, and
`MediaSource.attach` contains an `unregisterCallback(emptyCallback)` that never
had a matching `registerCallback` — all suspect leftovers.

---

## 8. The overlay window

Created in `IslandOverlayService.showIsland()`:

- A `ComposeView` with `ViewCompositionStrategy.DisposeOnDetachedFromWindow` and
  `setLayerType(LAYER_TYPE_HARDWARE)` (GPU-composited; avoids re-rasterisation
  that pixelated the growing stack).
- Wrapped in `OverlayTouchRoot` (a `FrameLayout` subclass) whose
  `dispatchTouchEvent` catches `ACTION_OUTSIDE` and calls
  `IslandExpansion.collapse()`.
- `WindowManager.LayoutParams`:
  - `WRAP_CONTENT` × `WRAP_CONTENT`
  - type `TYPE_APPLICATION_OVERLAY`
  - flags: `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN |
    FLAG_WATCH_OUTSIDE_TOUCH`
  - `PixelFormat.TRANSLUCENT`
  - gravity `TOP | CENTER_HORIZONTAL`, `y = topGapPx`
  - `alpha = 1f`, `windowAnimations = 0` (no system resize transition)
  - cutout policy: API 30+ `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` **and**
    `setFitInsetsTypes(0)`; API 28–29 `SHORT_EDGES`.

Deliberate choices baked in:
- The window is deliberately **not** `FLAG_NOT_TOUCHABLE`. HiOS forces a 0.8
  alpha onto non-touchable overlays (that is why the pill looked translucent
  instead of true black). Keeping it touchable is what makes it opaque.
- **Not focusable** → it can never receive the IME. This is the root cause of the
  "can't type a reply" problem (§14.4).

### 8.1 Geometry (`IslandGeometry`)

| Constant | Value (dp) | Use |
| --- | --- | --- |
| `TOP_GAP_DP` | 4 | gap from top edge to capsule |
| `IDLE_WIDTH_DP` | 116 | idle pill width |
| `PILL_HEIGHT_DP` | 32 | collapsed pill height |
| `CHARGING_WIDTH_DP` | 140 | (legacy) charging capsule width |
| `CHARGING_HEIGHT_DP` | 34 | (legacy) |
| `ACTIVE_EXTRA_WIDTH_DP` | 24 | width added when content is present |
| `CUTOUT_CLEARANCE_DP` | 48 | horizontal clearance for the punch hole |
| `EXPANDED_WIDTH_DP` | 320 | expanded panel (HiOS clamps overlays ≈320 dp) |
| `TILE_HEIGHT_DP` | 46 | generic activity tile height |
| `TILE_SPACING_DP` | 6 | gap between tiles |
| `TILE_RADIUS_DP` | 22 | tile corner radius |
| `PANEL_PADDING_DP` | 8 | panel padding |
| `PANEL_RADIUS_DP` | 30 | panel radius |
| `EXPANDED_TOP_OFFSET_DP` | 44 | how far the stack drops when expanded |
| `TAP_ZONE_HEIGHT_DP` | 30 | invisible tap strip below the pill |
| `COMPACT_WIDTH_DP` | 140 | legacy alias |
| `EXPANDED_HEIGHT_DP` | 175 | legacy |

Calibrated for the TECNO SPARK 50 (density 1.75×, 280 dpi, punch-hole reaches
~72 px from the top). The control centre exposes top-gap (0–24), pill height
(30–48), idle width (60–120) sliders and a reset.

---

## 9. Rendering and UI

### 9.1 `IslandHost`

Wraps everything in a bare `MaterialTheme`. It:
- collects `IslandExpansion.expanded` and `IslandSelection.selectedId`,
- collapses and clears selection when there is no content,
- auto-selects the top activity, but lets a higher-priority activity take over
  (`shouldTakeOver = prioritized && score(top) > score(current)`),
- computes `focused`, `showPanel = expanded && hasContent`,
  `visible = showIdlePill || hasContent` (returns early if not visible),
- animates the whole stack downward on expansion via `dropDown`
  (`animateDpAsState` spring, damping 0.82, StiffnessMediumLow),
- cross-fades collapsed↔expanded with `AnimatedContent`
  (`fadeIn(190) togetherWith fadeOut(130)`).

### 9.2 `CollapsedPill`

- Renders the charging glow (see §11/§14.2), intro scale, and the tap/swipe
  gesture modifier.
- Background uses `solidIsland`: opaque near-black vertical gradient
  (`0xFF232329 → 0xFF0E0E12 → 0xFF020203`) with a top-lit 1 dp rim gradient.
- Cross-fades between activities via `AnimatedContent` (`fadeIn(160) /
  fadeOut(120)`).
- Contains `CollapsedContent`: a `BoxWithConstraints` row with the primary glyph
  (left), a `CUTOUT_CLEARANCE_DP` spacer, and the trailing value (right, only if
  `maxWidth >= 92.dp`). `PageDots` renders when more than one activity exists.

### 9.3 `ExpandedPanel`

- Reorders so the focused activity is on top when the stack feature is on.
- Renders one tile per activity: `MediaTile` for media, `NotificationTile` for
  notifications, `ActivityTile` otherwise. Each is wrapped in `TileEntrance`.
- There is deliberately **no shared panel background** — each tile is a separate
  card with screen showing through the gaps.

### 9.4 Tiles

- `ActivityTile` — a row: icon, title/subtitle column, optional trailing value,
  optional media transport glyph. Tap launches the app if a launch intent exists.
- `NotificationTile` — icon + title/text, then a row of `QuickActionChip`s:
  "Open" plus up to two notification actions. (State variables `replyIndex` /
  `replyText` for an inline reply field still exist but are unused — the inline
  field was removed.)
- `MediaTile` — artwork, title/artist, and previous / play-pause / next controls
  (`MediaSource.previous()`, `toggle()`, `next()`).

### 9.5 Visual materials

- `solidIsland` — opaque "cast metal" black gradient + bright rim (idle pill).
- `glassIsland` — layered translucent pane: back `0xB30F141F`, vertical
  `0x5C/0x28/0x0FFFFFFF`, horizontal `0x28/0x00/0x1AFFFFFF`, and a 1 dp rim
  gradient `0xD9FFFFFF → 0x66FFFFFF → 0x1A000000`. Physical backdrop blur is
  impossible for a `TYPE_APPLICATION_OVERLAY` window, so this fakes frosted glass.
- `sheenOverlay` — a moving specular band drawn over a tile.

### 9.6 Glyphs (all `Canvas`-drawn, no image assets)

`BatteryGlyph` (outline + nub + animated green fill + lightning bolt),
`EarbudGlyph` (two buds with stems), `PreviousGlyph`, `NextGlyph`,
`MediaTransportGlyph` (play triangle / pause bars), `AppIcon` (bitmap with
fallback circle). Accent colors: charging green `0xFF30D158`, Bluetooth blue
`0xFF4C9AFF`, notification cyan `0xFF64D2FF`, media pink `0xFFFF375F`.

---

## 10. Interaction and gestures

- **Collapsed pill** `islandGestures`: a `pointerInput` loop.
  - When the stack is off, a plain `detectTapGestures` toggles expansion.
  - When on, it reads down/move/up: a horizontal drag past 36 dp (and larger than
    vertical) cycles activities; a movement under touch-slop is a tap (expand).
- **Tap strip** below the pill (30 dp) also toggles expansion via the same gesture
  modifier (the whole `Column` has it).
- **Tap outside** the window collapses it (`OverlayTouchRoot.ACTION_OUTSIDE`).
- **Tap a tile** launches the source app (or the media/notification content
  intent).
- **Tap a quick-action chip** fires the notification action.

---

## 11. Animations inventory (complete)

This is the full list of every animation in the app — relevant because animations
and resource use are the current focus.

| # | Location | Type | Trigger | Notes |
| --- | --- | --- | --- | --- |
| 1 | `IslandHost` `dropDown` | `animateDpAsState`, spring (0.82, MediumLow) | expand/collapse | 0 → 44 dp top offset |
| 2 | `IslandHost` `AnimatedContent` "islandMode" | crossfade fadeIn 190 / fadeOut 130 | expand/collapse | collapsed ↔ expanded |
| 3 | `CollapsedPill` `chargingGlow()` | **`rememberInfiniteTransition`**, 900 ms Reverse | charging | **CONTINUOUS — idle-CPU bug (§14.2)** |
| 4 | `CollapsedPill` `chargingIntro(active)` | one-shot `Animatable` + spring (0.45, Low) | charging appears | 1.25× → 1.0× |
| 5 | `CollapsedPill` `AnimatedContent` "pillActivity" | crossfade fadeIn 160 / fadeOut 120 | activity change | content swap |
| 6 | `BatteryGlyph` fill | `animateFloatAsState` spring (0.65, 220) | battery % change | fill fraction |
| 7 | `TileEntrance` | `AnimatedVisibility` fadeIn 200 + `slideInVertically` spring (0.7, MediumLow) | tile appears | staggered `delay(index*55)` |
| 8 | `rememberSheen` / `sheenOverlay` (NotificationTile) | `Animatable` tween 500 | tile appears | delay `index*40`, one-shot |
| 9 | `ActivityTile` inline sheen | `Animatable` tween 500 | tile appears | delay `index*40`, one-shot (duplicated logic) |
| 10 | `PageDots` | none | — | dot size changes without animation |

Reduced-motion paths: when `reduceEffects` is true, #3 becomes a static 0.45 rim,
#4 is pinned to 1.0, #7 reveals instantly, and #8/#9 jump to 1.0 immediately.

Non-animation loops that are **not** redraw sources: the `while (true)` inside
`islandGestures` (event-driven pointer loop) and the `LaunchedEffect` collectors.

---

## 12. Feature catalog and control centre

### 12.1 Catalog (`FeatureCatalog.definitions`)

**CORE**
- `persistent_pill` — master switch, `isMaster`, requiresOverlay, default ON.
- `activity_prioritization` — default ON, ranks activities.
- `activity_stack` — default ON, side-by-side/swipe.
- `low_power_mode` — default ON, pauses effects/listeners.

**SYSTEM**
- `charging` — requiresOverlay, default OFF.
- `bluetooth` — requiresOverlay, default OFF.

**MEDIA**
- `music_playback` — capability UNKNOWN, ownership DYNAMIC_PORT, cost HIGH.
- `calls` — capability UNKNOWN, ownership DYNAMIC_PORT (not implemented).
- `notifications` — capability UNKNOWN, ownership UNDECIDED, cost HIGH.

**LIVE**
- `quick_actions` — default ON.
- `navigation` — not implemented.
- `delivery` — not implemented.

**PRIVACY**
- `privacy_shield` — default OFF.
- `discreet_mode` — default OFF.

Each definition carries a `FeatureCost` and a `resourceNote`.

### 12.2 Control centre UI

`FeatureControlScreen` (a `LazyColumn`): `ProfileCard` (device/system/resolution/
density/dp/cutout/overlay-permission), `PositioningCard` (geometry sliders +
reset), `MasterCard`, then a section per `FeatureGroup` with a `FeatureRow` per
feature (switch + status label + ownership chips), and finally `DiagnosticsCard`.

`DiagnosticsCard` shows battery mode (power-save on/off), "active features X of
Y", and a color-coded row per active feature (LOW green / MEDIUM amber / HIGH red)
with its cost label and resource note.

`FeatureControlViewModel` merges per-feature DataStore flows
(`enabledFlow`, `ownershipFlow`) with the live overlay-permission StateFlow into
`FeatureUiState`s. `overlayShouldRun = (any enabled && requiresOverlay) &&
overlayGranted` — this is what gates the service lifecycle, so e.g. charging can
show without the master pill.

Important limitation: **`ownership` and `capability` are display-only.** The
runtime never consults ownership, and `capability` is the static default (real
probes are not implemented; `UNKNOWN` never resolves).

---

## 13. Persistence

`FeaturePreferences` over a Preferences DataStore named
`adaptive_island_settings`. Keys: `enabled_<featureId>`,
`ownership_<featureId>`, `top_gap_dp`, `pill_height_dp`, `idle_width_dp`. Read
errors are swallowed to `emptyPreferences` on `IOException`. `pillHeightDpFlow`
coerces up to at least the default; the VM coerces slider ranges (top gap 0–30,
pill 28–50, idle width 60–140).

---

### 14. Known bugs & Resolutions

### 14.1 RESOLVED — tapping a notification does not open the app (Android BAL block & Package Visibility)

**Root Causes:**
1. Android 11+ (API 30+) package visibility (`AppsFilter`) blocked querying the launch intent of third-party apps like WhatsApp.
2. Android 14/15/16 background-activity-launch rules blocked sending another app's content `PendingIntent` without sender opt-in.

**Fix Applied:**
- Added `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />` and `<queries>` block in `AndroidManifest.xml`.
- In `NotificationTile.open`: Attempts direct `context.startActivity(launchable)` first.
- In `NotificationSource.open`: Uses `ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED` on API 34+ when sending `PendingIntent`s as a fallback.

### 14.2 RESOLVED — idle redraw / CPU while charging

**Fix Applied & Verified:**
- Replaced continuous `rememberInfiniteTransition` breathing glow with a static border and one-shot `chargingIntro` spring.
- Verified on-device with `dumpsys gfxinfo dev.personal.adaptiveisland2`: measured 0 redraws over idle intervals during charging.

### 14.3 Status-bar overlap & Pill touch geometry

**Root Cause:**
Android InputDispatcher routes touch events strictly by window layer. The system `StatusBar` (`TYPE_STATUS_BAR`, layer 141,000) spans `y = 0..72 px` (display cutout safe zone) and physically sits above any third-party overlay (`TYPE_APPLICATION_OVERLAY`, layer 111,000). Built-in OEM features like Transsion "Dynamic Port" run directly inside the system's `com.android.systemui` status bar process, which is why they receive touches on the camera glass. Third-party overlays cannot overlay the status bar without OS-level root/modifications (which are strictly out-of-bounds).

**Solution Applied:**
- Increased calibrated default `PILL_HEIGHT_DP` to `40dp` (70 px). Anchored with `TOP_GAP_DP = 4dp` (7 px), the pill capsule now reaches down to `y = 77 px`, physically crossing past the 72px StatusBar window into the touchable region.
- Integrated the tap zone for both active and idle states, allowing touching the pill capsule directly on/around the camera hole to register immediately without reaching far below.

### 14.4 Inline reply typing

The overlay window is `FLAG_NOT_FOCUSABLE` to remain non-modal. RemoteInput quick action launches the posting app directly to its composer.

### 14.5 RESOLVED — Quick-action chips: "Reply" / "Mark as read"

**Fix Applied:**
- Attached `RemoteInput` results bundle to the `fillInIntent` when text is provided.
- Added `ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED` on API 34+ so quick action `PendingIntent`s are not BAL-blocked.
- Cleaned up redundant branch logic in `NotificationSource.fireAction`.

### 14.6 RESOLVED — Gesture reliability & Tactile physics

**Fix Applied:**
- `islandGestures` overhauled with reliable pointer tracking, consuming drag events, and handling cancellation safely.
- Added tactile press feedback: collapsed pill springs down to `0.96x` on touch down and pops open on release.
- **Swipe-to-Dismiss on Notification Tiles**: Notification cards can now be swiped horizontally to the left or right. Flings/drags past 72dp dismiss the notification both from the island and from the system notification shade via `IslandNotificationListener.cancelNotification()`, with fluid spring collapse of remaining cards.

### 14.7 RESOLVED — Minor / code health

- Implemented reactive `MediaController.Callback` in `MediaSource` to listen to playback and metadata changes live; cleaned up unregistered callbacks.
- Removed dead duplicated functions `toMedia` and `toMediaCompat`.
- Removed empty scaffolding directories `diagnostics/`, `lifecycle/`, `ownership/`.

---

## 15. What is implemented vs. not

| Feature | Status |
| --- | --- |
| Persistent pill, expansion, collapse, gestures | Implemented |
| Charging activity (glyph, %, glow) | Implemented (glow buggy §14.2) |
| Bluetooth activity (device, battery via reflection) | Implemented |
| Media activity + transport controls + artwork | Implemented (needs live-session verification) |
| Notification activity (one per app, top 3) | Implemented |
| Activity prioritization (call > timer > nav > media > actionable > notif > charging > bt) | Implemented |
| Activity stack + swipe cycling + remembered selection | Implemented |
| Contextual quick actions (Open + up to 2 actions) | Implemented but launching unreliable (§14.1/14.5) |
| Privacy shield / Discreet mode (mask text) | Implemented |
| Adaptive low-power mode | Implemented |
| Resource diagnostics card | Implemented |
| Calls activity | **Not implemented (catalog only)** |
| Navigation activity | **Not implemented (catalog only)** |
| Delivery activity | **Not implemented (catalog only)** |
| "Dynamic Port" ownership / takeover | **Not implemented (config only)** |
| Inline reply / typing | **Removed; blocked by non-focusable window** |
| Device capability probing (resolve UNKNOWN) | **Not implemented** |
| iOS-grade backdrop blur | **Not possible for overlay windows** |
| Automated tests | **None** |

---

## 16. Measured resource usage (TECNO SPARK 50, Android 16/API 36)

Collected during debugging (`top`, `dumpsys gfxinfo`, `dumpsys meminfo`):

- Process CPU while charging + idle: 26–50 % (main) + ~13 % (RenderThread).
- Redraws while charging + idle: ~100 fps (≈512 frames / 5 s).
- Redraws while not charging + idle: 0 frames / 5 s.
- gfxinfo totals across a session: 67,250 → 164,264 frames; Janky 67 (0.04 %),
  legacy janky 2,424 (1.49 %), Missed Vsync 49. "High input latency" reported
  324,698 (anomalously high vs frame count — metric suspected, not trusted).
- PSS baseline ≈ 106,088 KB (GL mtrack 26,408 KB, SwapPss 55,927 KB).
- Threads: ~50.

Interpretation: the app is idle-cheap **except** the charging glow, which alone
keeps the GPU pipeline at full refresh rate. Fixing §14.2 should drive idle CPU
and redraws to near zero while charging.

---

## 17. Constraints and hard limits

- **No backdrop blur.** `TYPE_APPLICATION_OVERLAY` windows cannot sample what is
  behind them, so true iOS "frosted glass" is impossible; the glass is faked with
  layered translucency and highlights.
- **BAL restrictions (Android 15/16, targetSdk 36).** Background launching of
  another app's `PendingIntent` requires the creator's opt-in; SystemUI gets a
  short notification allowlist, a third-party overlay does not. Direct
  `startActivity` from our own uid (visible non-app window + FGS) is the reliable
  path.
- **HiOS quirks.** Non-touchable overlays are forced to 0.8 alpha; overlay width
  is clamped (~320 dp); status bar occupies the top ~72 px.
- **Bluetooth battery.** Only one aggregate value, and only if the accessory
  reports it; per-bud values are not exposed.
- **Notification access** is a user-toggled Settings value, not a runtime
  permission.
- **Clipboard actions** are not possible from a non-focusable overlay window.
- **Shell cannot impersonate packages** for `cmd notification post`, so test
  notifications from the shell have no content intent.

---

## 18. Build, install, and debug

From the repo root (Windows / PowerShell):

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
.\gradlew.bat assembleRelease
```

On-device debugging commands used throughout:

```powershell
# overlay permission / launch
adb shell appops set dev.personal.adaptiveisland2 SYSTEM_ALERT_WINDOW allow
adb shell am start -n dev.personal.adaptiveisland2/dev.personal.adaptiveisland.app.MainActivity

# simulate a tap inside the expanded island
adb shell "input tap 360 90 && sleep 1 && input tap 250 200"

# watch the launch decision
adb logcat -d | Select-String "IslandQuickAction|START u0|Background activity"

# confirm what is on top
adb shell dumpsys activity activities | Select-String topResumedActivity

# frame stats (redraw behaviour)
adb shell dumpsys gfxinfo dev.personal.adaptiveisland2

# fake battery state for glow testing (reset afterwards)
adb shell dumpsys battery set status 3
adb shell dumpsys battery reset
```

Note: `dumpsys battery` does not support a `plugged` set option on this device.

---

## 19. Roadmap — where to focus

Ordered by impact for the current goals (animations + incomplete features):

1. **Fix tap-to-open (§14.1).** Small, high-impact; makes the whole notification
   feature usable. Direct `startActivity` first, log every path.
2. **Kill the charging-glow idle redraw (§14.2).** Biggest resource win. Static
   rim + one-shot intro. Verify via gfxinfo delta.
3. **Make quick actions reliable (§14.5).** Decide per action whether it is an
   activity PI (may need opening the app instead) vs a broadcast/service PI.
4. **Restore inline reply (§14.4).** Toggle window focusability while composing;
   request focus; show IME; restore flags after.
5. **Modernize the animation system (§11).** Replace the ad-hoc per-tile sheens
   with one shared, coordinated motion language; consider `AnimatedContent`
   `SizeTransform` for smoother size changes; make the sheen optional and cheaper.
6. **Polish the glass/3D look (§9.5).** Given blur is impossible, deepen the
   layered highlights and rim lighting for a more three-dimensional surface.
7. **Implement or remove the stub features (calls/navigation/delivery).** Either
   wire real notification-category providers or drop them from the catalog and
   the "UNKNOWN capability" state.
8. **Make ownership actually do something** or label it clearly as planning-only.
9. **Add tests** (at minimum unit tests for `ActivityPriority`, the notification
   filter pipeline, and `IslandGeometry` math) and wire a lint/typecheck step.
10. **Housekeeping (§14.7):** remove dead media code, delete empty packages,
    normalize the mojibaked strings.
```
