package dev.personal.adaptiveisland.overlay

import android.content.Context
import android.content.Intent
import android.os.Vibrator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.personal.adaptiveisland.integrations.BatterySource
import dev.personal.adaptiveisland.integrations.BatteryState
import dev.personal.adaptiveisland.integrations.BluetoothSource
import dev.personal.adaptiveisland.integrations.BluetoothState
import dev.personal.adaptiveisland.integrations.CallSource
import dev.personal.adaptiveisland.integrations.CallState
import dev.personal.adaptiveisland.integrations.MAX_NOTIFICATION_TILES
import dev.personal.adaptiveisland.integrations.NavigationSource
import dev.personal.adaptiveisland.integrations.NavigationState
import dev.personal.adaptiveisland.integrations.TimerSource
import dev.personal.adaptiveisland.integrations.TimerState
import dev.personal.adaptiveisland.integrations.MediaSource
import dev.personal.adaptiveisland.integrations.NotificationSource
import dev.personal.adaptiveisland.persistence.FeaturePreferences
import kotlinx.coroutines.delay

/** Everything the island needs to decide what to present. */
data class IslandState(
    val showIdlePill: Boolean,
    val activities: List<IslandActivity>,
    val topGapDp: Int = IslandGeometry.TOP_GAP_DP,
    val pillHeightDp: Int = IslandGeometry.PILL_HEIGHT_DP,
    val idleWidthDp: Int = IslandGeometry.IDLE_WIDTH_DP,
) {
    val charging: IslandActivity.Charging?
        get() = activities.filterIsInstance<IslandActivity.Charging>().firstOrNull()

    val notificationCount: Int
        get() = activities.count { it is IslandActivity.Notification }

    /** Newest activity drives the compact pill. */
    val primary: IslandActivity?
        get() = activities.firstOrNull()

    val hasContent: Boolean
        get() = activities.isNotEmpty()
}

private const val INTRO_SCALE = 1.25f

private val ChargingGreen = Color(0xFF30D158)
private val BluetoothBlue = Color(0xFF4C9AFF)
private val NotificationAccent = Color(0xFF64D2FF)
private val MediaAccent = Color(0xFFFF375F)
private val TimerAccent = Color(0xFF9B5DE5)
private val NavigationAccent = Color(0xFF00D2FF)
private val BoltWhite = Color.White
private val MutedText = Color(0xFF9AA0AE)

/**
 * Opaque, solid "cast metal" black with a top-lit gradient and a bright rim —
 * the tactile, almost-3D look used by the idle pill.
 */
private fun Modifier.solidIsland(shape: Shape): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            colors = listOf(Color(0xFF232329), Color(0xFF0E0E12), Color(0xFF020203)),
        ),
    )
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0x59FFFFFF), Color(0x0FFFFFFF), Color(0x08FFFFFF)),
        ),
        shape = shape,
    )

/**
 * Translucent iOS-style "liquid glass": a layered frosted pane with a specular
 * top highlight and a hairline rim. Physical backdrop blur is not available to
 * a TYPE_APPLICATION_OVERLAY window, so this fakes the material with layered
 * translucency and highlights.
 */
private fun Modifier.glassIsland(shape: Shape): Modifier = this
    .clip(shape)
    .background(Color(0x8C0C0C13))
    .background(
        Brush.verticalGradient(
            colors = listOf(Color(0x38FFFFFF), Color(0x14FFFFFF), Color(0x00FFFFFF)),
        ),
    )
    .background(
        Brush.horizontalGradient(
            colors = listOf(Color(0x1AFFFFFF), Color(0x00FFFFFF), Color(0x14FFFFFF)),
        ),
    )
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0x80FFFFFF), Color(0x24FFFFFF), Color(0x0FFFFFFF)),
        ),
        shape = shape,
    )

@Composable
fun rememberIslandState(): IslandState {
    val context = LocalContext.current
    val prefs = remember { FeaturePreferences(context.applicationContext) }
    val showIdlePill by prefs.enabledFlow("persistent_pill").collectAsState(initial = true)
    val showCharging by prefs.enabledFlow("charging").collectAsState(initial = false)
    val showBluetooth by prefs.enabledFlow("bluetooth").collectAsState(initial = false)
    val showCalls by prefs.enabledFlow("calls").collectAsState(initial = false)
    val showNotifications by prefs.enabledFlow("notifications").collectAsState(initial = false)
    val showMedia by prefs.enabledFlow("music_playback").collectAsState(initial = false)
    val prioritize by prefs.enabledFlow("activity_prioritization").collectAsState(initial = true)
    val battery by BatterySource.observe(context.applicationContext).collectAsState(
        initial = BatteryState(levelPercent = 0, plugged = false, charging = false, full = false),
    )
    val bluetooth by BluetoothSource.observe(context.applicationContext).collectAsState(
        initial = BluetoothState(),
    )
    val calls by CallSource.observe(context.applicationContext).collectAsState(initial = CallState())
    val showTimer by prefs.enabledFlow("timer").collectAsState(initial = false)
    val timerState by TimerSource.observe().collectAsState(initial = TimerState())
    val showNavigation by prefs.enabledFlow("navigation").collectAsState(initial = false)
    val navigation by NavigationSource.state.collectAsState(initial = NavigationState())
    val notifications by NotificationSource.notifications.collectAsState()
    val media by MediaSource.nowPlaying.collectAsState()
    val topGapDp by prefs.topGapDpFlow().collectAsState(initial = IslandGeometry.TOP_GAP_DP)
    val pillHeightDp by prefs.pillHeightDpFlow().collectAsState(initial = IslandGeometry.PILL_HEIGHT_DP)
    val idleWidthDp by prefs.idleWidthDpFlow().collectAsState(initial = IslandGeometry.IDLE_WIDTH_DP)

    val activities = buildList {
        if (showCharging == true && battery.plugged) {
            add(IslandActivity.Charging(battery.levelPercent, battery.charging, battery.full))
        }
        if (showBluetooth == true && bluetooth.connected) {
            add(IslandActivity.Bluetooth(bluetooth.deviceName, bluetooth.batteryPercent))
        }
        if (showCalls == true && calls.active) {
            add(IslandActivity.Call(calls.active, calls.callState, calls.handle))
        }
        if (showTimer == true) {
            val t = timerState
            if (t.running || t.elapsedMillis > 0) {
                add(
                    IslandActivity.Timer(
                        elapsedMillis = t.elapsedMillis,
                        running = t.running,
                        countdown = t.countdown,
                        targetMillis = t.targetMillis,
                        finished = t.finished,
                    ),
                )
            }
        }
        if (showNavigation == true && navigation.active) {
            add(
                IslandActivity.Navigation(
                    nextTurn = navigation.nextTurn,
                    distanceMeters = navigation.distanceMeters,
                    etaMillis = navigation.etaMillis,
                    active = navigation.active,
                ),
            )
        }
        if (showMedia == true) {
            media?.let { item ->
                add(
                    IslandActivity.Media(
                        packageName = item.packageName,
                        appLabel = item.appLabel,
                        title = item.title,
                        artist = item.artist,
                        icon = item.icon,
                        isPlaying = item.isPlaying,
                    ),
                )
            }
        }
        if (showNotifications == true) {
            notifications.take(MAX_NOTIFICATION_TILES).forEach { item ->
                add(
                    IslandActivity.Notification(
                        key = item.key,
                        packageName = item.packageName,
                        appLabel = item.appLabel,
                        title = item.title,
                        text = item.text,
                        icon = item.icon,
                        category = item.category,
                        hasActions = item.hasActions,
                    ),
                )
            }
        }
    }

    // When prioritization is on (the default), rank what deserves attention:
    // calls over music, navigation/timers above, action-enabled apps on top.
    // sortedWith is stable, so equal scores keep their source order (which the
    // notification listener already sorted by rank then recency).
    val ordered = if (prioritize != false) {
        activities.sortedWith(
            compareByDescending<IslandActivity> { ActivityPriority.score(it) },
        )
    } else {
        activities
    }

    return IslandState(
        showIdlePill = showIdlePill ?: true,
        activities = ordered,
        topGapDp = topGapDp,
        pillHeightDp = pillHeightDp,
        idleWidthDp = idleWidthDp,
    )
}

/**
 * Main host composable.
 *
 * Collapsed: a black pill over the camera cutout with a compact summary of the
 * newest activity.
 * Expanded: the pill morphs into a rounded panel holding one tile per live
 * activity — width, height and corner radius animate together so the island
 * physically reshapes instead of cross-fading between two unrelated layouts.
 */
@Composable
fun IslandHost(state: IslandState) {
    MaterialTheme {
        val context = LocalContext.current
        val expanded by IslandExpansion.expanded.collectAsState()
        val hasContent = state.hasContent
        LaunchedEffect(hasContent) { if (!hasContent) IslandExpansion.collapse() }
        val showPanel = expanded && hasContent
        val visible = state.showIdlePill || hasContent

        if (!visible) return@MaterialTheme

        val spacing = IslandGeometry.TILE_SPACING_DP.dp
        val collapsedWidth = state.idleWidthDp.dp +
            if (hasContent) IslandGeometry.ACTIVE_EXTRA_WIDTH_DP.dp else 0.dp
        val pillHeight = state.pillHeightDp.dp
        val tapZone = IslandGeometry.TAP_ZONE_HEIGHT_DP.dp

        // Morph progress: 0 = pill, 1 = expanded panel. Drives width / radius /
        // height so the island reshapes like iOS.
        val expandProgress by animateFloatAsState(
            targetValue = if (showPanel) 1f else 0f,
            animationSpec = spring(
                dampingRatio = 0.7f,
                stiffness = Spring.StiffnessHigh,
            ),
            label = "expandMorph",
        )

        // Haptic tick on expand / collapse.
        LaunchedEffect(expanded) {
            vibrate(context, if (expanded) 45 else 25)
        }

        // One-time pop entrance when the island first appears — like iOS
        // springing the pill into view.
        val introScale by animateFloatAsState(
            targetValue = if (visible) 1f else 0.85f,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            label = "introPop",
        )

        // Subtle breathing pulse when there is active content — draws attention
        // like iOS without being distracting.
        val breathTransition = rememberInfiniteTransition()
        val breathScale by breathTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breathScale",
        )

        // Combined scale: intro pop + breath when active + press-down later.
        val pillScale = introScale * (if (hasContent) breathScale else 1f)

        // The whole stack drops smoothly as the island expands, so the top card
        // clears the status bar / camera cutout.
        val dropDown by animateDpAsState(
            targetValue = if (showPanel) IslandGeometry.EXPANDED_TOP_OFFSET_DP.dp else 0.dp,
            animationSpec = spring(
                dampingRatio = 0.7f,
                stiffness = Spring.StiffnessHigh,
            ),
            label = "expandDrop",
        )

        // Interpolated pill → panel dimensions.
        val morphWidth by animateDpAsState(
            targetValue = if (showPanel) IslandGeometry.EXPANDED_WIDTH_DP.dp else collapsedWidth,
            animationSpec = spring(
                dampingRatio = 0.7f,
                stiffness = Spring.StiffnessHigh,
            ),
            label = "morphWidth",
        )
        val morphRadius by animateDpAsState(
            targetValue = if (showPanel) IslandGeometry.TILE_RADIUS_DP.dp else (pillHeight / 2),
            animationSpec = spring(
                dampingRatio = 0.7f,
                stiffness = Spring.StiffnessHigh,
            ),
            label = "morphRadius",
        )
        val morphHeight by animateDpAsState(
            targetValue = if (showPanel) expandedPanelHeight(state) else pillHeight,
            animationSpec = spring(
                dampingRatio = 0.7f,
                stiffness = Spring.StiffnessHigh,
            ),
            label = "morphHeight",
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(dropDown))

            // Single morphing container: pill on the left side of the progress,
            // rounded panel on the right side. Width, radius and background all
            // interpolate together.
            Box(
                modifier = Modifier
                    .width(morphWidth)
                    .height(morphHeight)
                    .graphicsLayer(scaleX = pillScale, scaleY = pillScale)
                    .morphSurface(
                        radius = morphRadius,
                        charging = state.charging,
                        progress = expandProgress,
                    )
                    .pointerInput(Unit) {
                        // Tap anywhere on the pill to expand; long-press to launch
                        // the source app. Only active when the island is collapsed.
                        detectTapGestures(
                            onTap = { if (!showPanel) IslandExpansion.expand() },
                            onLongPress = {
                                if (!showPanel) {
                                    launchPrimaryApp(context, state.primary)
                                    IslandExpansion.collapse()
                                }
                            },
                        )
                    },
            ) {
                // Cross-fade content as the morph progresses: glyph when collapsed,
                // tiles when expanded.
                AnimatedContent(
                    targetState = showPanel,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(160)) togetherWith
                            fadeOut(animationSpec = tween(120))
                    },
                    label = "islandContent",
                ) { panel ->
                    if (panel) {
                        ExpandedPanel(
                            state = state,
                            spacing = spacing,
                        )
                    } else {
                        CollapsedContent(state)
                    }
                }
            }

            // Transparent tap strip below the pill — expand on tap (collapsed only).
            if (state.hasContent && !showPanel) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tapZone)
                        .pointerInput(Unit) {
                            detectTapGestures { IslandExpansion.expand() }
                        },
                )
            }
        }
    }
}

/** Height the expanded panel needs to fit all its tiles. */
private fun expandedPanelHeight(state: IslandState): Dp {
    val count = state.activities.size
    if (count == 0) return IslandGeometry.TILE_HEIGHT_DP.dp
    return (count * IslandGeometry.TILE_HEIGHT_DP +
        (count - 1) * IslandGeometry.TILE_SPACING_DP +
        2 * IslandGeometry.PANEL_PADDING_DP).dp
}

/**
 * Surface for the morphing container: solid "cast metal" pill when collapsed,
 * translucent liquid-glass panel when expanded. Corner radius interpolates with
 * the morph progress.
 */
@Composable
private fun Modifier.morphSurface(
    radius: Dp,
    charging: IslandActivity.Charging?,
    progress: Float,
): Modifier {
    val glow = if (charging != null) chargingGlow() else 0f
    return this.then(if (progress < 0.5f) {
        // Pill phase: opaque black cast-metal capsule.
        solidIsland(RoundedCornerShape(radius))
            .then(
                if (charging != null) {
                    Modifier.border(
                        1.dp,
                        ChargingGreen.copy(alpha = glow),
                        RoundedCornerShape(radius),
                    )
                } else {
                    Modifier
                },
            )
    } else {
        // Panel phase: liquid glass.
        glassIsland(RoundedCornerShape(radius))
    })
}

/** Launch the source application of the primary island activity. */
private fun launchPrimaryApp(context: Context, primary: IslandActivity?) {
    val packageName = when (primary) {
        is IslandActivity.Notification -> primary.packageName
        is IslandActivity.Media -> primary.packageName
        else -> null
    }
    packageName?.let { pkg ->
        runCatching {
            context.packageManager
                .getLaunchIntentForPackage(pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull()?.let { intent ->
            context.startActivity(intent)
        }
    }
}

/** Brief haptic tick on expand / collapse. */
@Suppress("DEPRECATION")
private fun vibrate(context: Context, milliseconds: Int) {
    runCatching {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(milliseconds.toLong())
    }
}

@Composable
private fun CollapsedContent(state: IslandState) {
    val primary = state.primary ?: return
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp),
    ) {
        val glyphSize = (maxHeight * 0.44f).coerceIn(14.dp, 24.dp)
        val roomyEnough = maxWidth >= 92.dp
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                PrimaryGlyph(primary, glyphSize)
            }
            Spacer(Modifier.width(IslandGeometry.CUTOUT_CLEARANCE_DP.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (roomyEnough) PrimaryTrailing(primary, state.notificationCount)
            }
        }
    }
}

@Composable
private fun PrimaryGlyph(activity: IslandActivity, size: Dp) {
    when (activity) {
        is IslandActivity.Charging -> BatteryGlyph(
            percent = activity.level,
            modifier = Modifier.size(width = size * 1.6f, height = size),
        )
        is IslandActivity.Bluetooth -> EarbudGlyph(
            modifier = Modifier.size(size),
            color = BluetoothBlue,
        )
        is IslandActivity.Call -> CallGlyph(
            modifier = Modifier.size(size),
            color = ChargingGreen,
        )
        is IslandActivity.Navigation -> NavigationGlyph(
            modifier = Modifier.size(size),
            color = NavigationAccent,
        )
        is IslandActivity.Timer -> TimerGlyph(
            activity,
            modifier = Modifier.size(size),
            color = TimerAccent,
        )
        is IslandActivity.Stopwatch -> TimerGlyph(
            activity,
            modifier = Modifier.size(size),
            color = TimerAccent,
        )
        is IslandActivity.Notification -> AppIcon(
            bitmap = activity.icon,
            size = size,
            fallbackColor = NotificationAccent,
        )
        is IslandActivity.Media -> AppIcon(
            bitmap = activity.icon,
            size = size,
            fallbackColor = MediaAccent,
        )
    }
}

@Composable
private fun PrimaryTrailing(activity: IslandActivity, notificationCount: Int) {
    when (activity) {
        is IslandActivity.Charging -> Label("${activity.level}%", ChargingGreen)
        is IslandActivity.Bluetooth ->
            activity.batteryPercent?.let { Label("$it%", BluetoothBlue) }
        is IslandActivity.Call -> Label("Call", ChargingGreen)
        is IslandActivity.Timer -> TimerTrailing(activity)
        is IslandActivity.Stopwatch -> TimerTrailing(activity)
        is IslandActivity.Navigation -> NavigationTrailing(activity)
        is IslandActivity.Notification ->
            if (notificationCount > 1) Label("$notificationCount", NotificationAccent) else Unit
        is IslandActivity.Media -> Unit
    }
}

@Composable
private fun Label(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

@Composable
private fun TimerTrailing(activity: IslandActivity) {
    val millis = when (activity) {
        is IslandActivity.Timer -> activity.remainingMillis
        is IslandActivity.Stopwatch -> activity.elapsedMillis
        else -> 0L
    }
    val totalSeconds = (millis / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    Label("%d:%02d".format(minutes, seconds), TimerAccent)
}

@Composable
private fun NavigationTrailing(activity: IslandActivity.Navigation) {
    val distance = activity.distanceMeters?.let { d ->
        if (d > 1000) "%.1f km".format(d / 1000f) else "%d m".format(d)
    } ?: "?"
    Label(distance, NavigationAccent)
}

/**
 * A stack of fully independent, content-sized cards: one standalone island per
 * activity, with real gaps between them (the screen shows through). There is
 * deliberately NO shared panel/background holding them together.
 */
@Composable
private fun ExpandedPanel(
    state: IslandState,
    spacing: Dp,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier.verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        state.activities.forEachIndexed { index, activity ->
            TileEntrance(index = index) {
                if (activity is IslandActivity.Media) {
            MediaTile(activity, index)
        } else {
            ActivityTile(activity, index)
        }
            }
        }
    }
}

@Composable
private fun TileEntrance(index: Int, content: @Composable () -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 55L)
        revealed = true
    }
    AnimatedVisibility(
        visible = revealed,
        enter = fadeIn(tween(200)) + slideInVertically(
            animationSpec = spring(
                dampingRatio = 0.7f,
                stiffness = Spring.StiffnessMediumLow,
            ),
            initialOffsetY = { -it / 3 },
        ),
    ) {
        content()
    }
}

@Composable
private fun MediaTile(activity: IslandActivity.Media, index: Int) {
    val shape = RoundedCornerShape(IslandGeometry.TILE_RADIUS_DP.dp)
    Column(
        modifier = Modifier
            .widthIn(min = 220.dp, max = 340.dp)
            .height(96.dp)
            .glassIsland(shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(bitmap = activity.icon, size = 40.dp, fallbackColor = MediaAccent)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activity.title,
                    color = BoltWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (activity.artist?.isNotBlank() == true) {
                    Text(
                        text = activity.artist,
                        color = MutedText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clickable { MediaSource.previous() },
                contentAlignment = Alignment.Center
            ) {
                PreviousGlyph(modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(20.dp))
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clickable { MediaSource.toggle() },
                contentAlignment = Alignment.Center
            ) {
                MediaTransportGlyph(
                    playing = activity.isPlaying,
                    modifier = Modifier.size(28.dp),
                    color = BoltWhite,
                )
            }
            Spacer(Modifier.width(20.dp))
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clickable { MediaSource.next() },
                contentAlignment = Alignment.Center
            ) {
                NextGlyph(modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ActivityTile(activity: IslandActivity, index: Int) {
    val context = LocalContext.current
    val launchPackage = when (activity) {
        is IslandActivity.Notification -> activity.packageName
        is IslandActivity.Media -> activity.packageName
        else -> null
    }
    val launchable = launchPackage?.let { pkg ->
        context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val shape = RoundedCornerShape(IslandGeometry.TILE_RADIUS_DP.dp)
    val tileTimerState by TimerSource.state.collectAsState(initial = TimerState())

    // One-shot liquid-glass sheen that sweeps across the card as it appears.
    val sheen = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 55L + 80L)
        sheen.animateTo(1f, tween(durationMillis = 850, easing = FastOutSlowInEasing))
    }

    Row(
        modifier = Modifier
            .height(IslandGeometry.TILE_HEIGHT_DP.dp)
            .widthIn(min = 150.dp, max = 300.dp)
            .glassIsland(shape)
            .drawWithContent {
                drawContent()
                val x = (sheen.value * 1.7f - 0.35f) * size.width
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x4DFFFFFF),
                            Color.Transparent,
                        ),
                        start = Offset(x - size.height, 0f),
                        end = Offset(x, size.height),
                    ),
                )
            }
            .then(
                if (launchable != null) {
                    Modifier.pointerInput(launchable) {
                        detectTapGestures {
                            runCatching { context.startActivity(launchable) }
                            IslandExpansion.collapse()
                        }
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (activity) {
                is IslandActivity.Charging -> BatteryGlyph(
                    percent = activity.level,
                    modifier = Modifier.size(width = 24.dp, height = 13.dp),
                )
                is IslandActivity.Bluetooth -> EarbudGlyph(
                    modifier = Modifier.size(20.dp),
                    color = BluetoothBlue,
                )
                is IslandActivity.Call -> CallGlyph(
                    modifier = Modifier.size(24.dp),
                    color = ChargingGreen,
                )
                is IslandActivity.Navigation -> NavigationGlyph(
                    modifier = Modifier.size(24.dp),
                    color = NavigationAccent,
                )
                is IslandActivity.Timer -> TimerGlyph(
                    activity,
                    modifier = Modifier.size(28.dp),
                    color = TimerAccent,
                )
                is IslandActivity.Stopwatch -> TimerGlyph(
                    activity,
                    modifier = Modifier.size(28.dp),
                    color = TimerAccent,
                )
                is IslandActivity.Notification -> AppIcon(
                    bitmap = activity.icon,
                    size = 28.dp,
                    fallbackColor = NotificationAccent,
                )
                is IslandActivity.Media -> AppIcon(
                    bitmap = activity.icon,
                    size = 28.dp,
                    fallbackColor = MediaAccent,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.widthIn(max = 200.dp)) {
            Text(
                text = activity.tileTitle(),
                color = BoltWhite,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = activity.tileSubtitle(),
                color = MutedText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(10.dp))

        activity.tileTrailing()?.let { trailing ->
            Text(
                text = trailing,
                color = activity.accent(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.width(2.dp))
        }

        if (activity is IslandActivity.Media) {
            Spacer(Modifier.width(2.dp))
            MediaTransportGlyph(
                playing = activity.isPlaying,
                modifier = Modifier.size(16.dp),
                color = BoltWhite,
            )
        }

        // In-island toggles for controllable activities.
        when (activity) {
            is IslandActivity.Timer -> {
                Spacer(Modifier.width(8.dp))
                val isRunning = tileTimerState.running
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable {
                            TimerSource.toggle()
                            IslandExpansion.collapse()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isRunning) {
                        // Pause glyph (two vertical bars)
                        Canvas(modifier = Modifier.size(16.dp)) {
                            val w = size.width
                            val h = size.height
                            val barW = w * 0.22f
                            val gap = w * 0.12f
                            val left = (w - (barW * 2f + gap)) / 2f
                            drawRoundRect(
                                color = TimerAccent,
                                topLeft = Offset(left, h * 0.25f),
                                size = Size(barW, h * 0.5f),
                                cornerRadius = CornerRadius(2f),
                                style = Fill,
                            )
                            drawRoundRect(
                                color = TimerAccent,
                                topLeft = Offset(left + barW + gap, h * 0.25f),
                                size = Size(barW, h * 0.5f),
                                cornerRadius = CornerRadius(2f),
                                style = Fill,
                            )
                        }
                    } else {
                        // Play glyph (triangle)
                        Canvas(modifier = Modifier.size(16.dp)) {
                            val w = size.width
                            val h = size.height
                            val path = Path().apply {
                                moveTo(w * 0.35f, h * 0.25f)
                                lineTo(w * 0.75f, h * 0.50f)
                                lineTo(w * 0.35f, h * 0.75f)
                                close()
                            }
                            drawPath(path = path, color = TimerAccent, style = Fill)
                        }
                    }
                }
            }
            is IslandActivity.Navigation -> {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clickable {
                            NavigationSource.clear()
                            IslandExpansion.collapse()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Clear glyph (X)
                    Canvas(modifier = Modifier.size(14.dp)) {
                        val w = size.width
                        val h = size.height
                        val stroke = 2f
                        drawLine(
                            color = NavigationAccent,
                            start = Offset(stroke, stroke),
                            end = Offset(w - stroke, h - stroke),
                            strokeWidth = stroke,
                        )
                        drawLine(
                            color = NavigationAccent,
                            start = Offset(w - stroke, stroke),
                            end = Offset(stroke, h - stroke),
                            strokeWidth = stroke,
                        )
                    }
                }
            }
            else -> Unit
        }
    }
}

/** Renders a large digital timer / stopwatch readout. */
@Composable
private fun TimerGlyph(
    activity: IslandActivity,
    modifier: Modifier = Modifier,
    color: Color = TimerAccent,
) {
    val millis = when (activity) {
        is IslandActivity.Timer -> activity.remainingMillis
        is IslandActivity.Stopwatch -> activity.elapsedMillis
        else -> 0L
    }
    val totalSeconds = (millis / 1000).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val tenths = ((millis % 1000) / 100).toInt()

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
            else "%d:%02d.%d".format(minutes, seconds, tenths),
            color = color,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun IslandActivity.tileTitle(): String = when (this) {
    is IslandActivity.Charging -> "Charging"
    is IslandActivity.Bluetooth -> deviceName ?: "Bluetooth audio"
    is IslandActivity.Call -> "Call"
    is IslandActivity.Timer -> if (finished) "Timer finished" else "Timer"
    is IslandActivity.Stopwatch -> "Stopwatch"
    is IslandActivity.Navigation -> nextTurn ?: "Navigation"
    is IslandActivity.Notification -> title
    is IslandActivity.Media -> title
}

private fun IslandActivity.tileSubtitle(): String = when (this) {
    is IslandActivity.Charging -> when {
        full -> "Battery full"
        charging -> "Cable connected"
        else -> "Plugged in"
    }
    is IslandActivity.Bluetooth -> "Connected"
    is IslandActivity.Call -> handle ?: "Incoming call"
    is IslandActivity.Timer -> if (running) "Counting down" else "Paused"
    is IslandActivity.Stopwatch -> if (running) "Running" else "Stopped"
    is IslandActivity.Navigation -> "ETA ${etaMillis?.let { (it / 1000 / 60).toInt() } ?: 0} min"
    is IslandActivity.Notification -> text.ifBlank { appLabel }
    is IslandActivity.Media -> artist?.takeIf { it.isNotBlank() } ?: appLabel
}

private fun IslandActivity.tileTrailing(): String? = when (this) {
    is IslandActivity.Charging -> "$level%"
    is IslandActivity.Bluetooth -> batteryPercent?.let { "$it%" } ?: "On"
    is IslandActivity.Call -> null
    is IslandActivity.Timer -> {
        val s = (remainingMillis / 1000).toInt()
        "%d:%02d".format(s / 60, s % 60)
    }
    is IslandActivity.Stopwatch -> {
        val s = (elapsedMillis / 1000).toInt()
        "%d:%02d".format(s / 60, s % 60)
    }
    is IslandActivity.Navigation -> distanceMeters?.let { d ->
        if (d > 1000) "%.1f km".format(d / 1000f) else "%d m".format(d)
    }
    is IslandActivity.Notification -> null
    is IslandActivity.Media -> null
}

private fun IslandActivity.accent(): Color = when (this) {
    is IslandActivity.Charging -> ChargingGreen
    is IslandActivity.Bluetooth -> BluetoothBlue
    is IslandActivity.Call -> ChargingGreen
    is IslandActivity.Timer -> TimerAccent
    is IslandActivity.Stopwatch -> TimerAccent
    is IslandActivity.Navigation -> NavigationAccent
    is IslandActivity.Notification -> NotificationAccent
    is IslandActivity.Media -> MediaAccent
}

@Composable
private fun AppIcon(bitmap: android.graphics.Bitmap?, size: Dp, fallbackColor: Color) {
    val shape = RoundedCornerShape(size.value.dp / 4)
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(fallbackColor.copy(alpha = 0.35f)),
        )
    }
}

@Composable
private fun chargingGlow(): Float {
    val transition = rememberInfiniteTransition(label = "chargeGlow")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )
    return alpha
}

@Composable
private fun chargingIntro(active: Boolean): Float {
    val anim = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (active) {
            anim.snapTo(INTRO_SCALE)
            anim.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow))
        } else {
            anim.snapTo(1f)
        }
    }
    return anim.value
}

@Composable
private fun PreviousGlyph(modifier: Modifier = Modifier, color: Color = BoltWhite) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.78f, h * 0.18f)
            lineTo(w * 0.78f, h * 0.82f)
            lineTo(w * 0.32f, h * 0.50f)
            close()
            moveTo(w * 0.24f, h * 0.18f)
            lineTo(w * 0.24f, h * 0.82f)
        }
        drawPath(path = path, color = color, style = Stroke(width = (w * 0.12f).coerceAtLeast(2f)))
    }
}

@Composable
private fun NextGlyph(modifier: Modifier = Modifier, color: Color = BoltWhite) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.18f)
            lineTo(w * 0.22f, h * 0.82f)
            lineTo(w * 0.68f, h * 0.50f)
            close()
            moveTo(w * 0.76f, h * 0.18f)
            lineTo(w * 0.76f, h * 0.82f)
        }
        drawPath(path = path, color = color, style = Stroke(width = (w * 0.12f).coerceAtLeast(2f)))
    }
}

/** Crisp pair of earbuds, used for the Bluetooth pill and tile. */
@Composable
private fun EarbudGlyph(modifier: Modifier = Modifier, color: Color = BoltWhite) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val budR = w * 0.20f
        val stemW = w * 0.13f
        val stemH = h * 0.46f

        listOf(w * 0.30f, w * 0.70f).forEach { cx ->
            drawCircle(color = color, radius = budR, center = Offset(cx, h * 0.32f))
            drawRoundRect(
                color = color,
                topLeft = Offset(cx - stemW / 2f, h * 0.32f),
                size = Size(stemW, stemH),
                cornerRadius = CornerRadius(stemW / 2f),
                style = Fill,
            )
        }
    }
}

/** Simple phone-handset outline for active calls. */
@Composable
private fun CallGlyph(modifier: Modifier = Modifier, color: Color = BoltWhite) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val bodyW = w * 0.72f
        val bodyH = h * 0.52f
        drawRoundRect(
            color = color,
            topLeft = Offset((w - bodyW) / 2f, (h - bodyH) / 2f),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(bodyH / 3f),
            style = Stroke(width = (w * 0.09f).coerceAtLeast(2f)),
        )
    }
}

/** Turn-arrow glyph for navigation. */
@Composable
private fun NavigationGlyph(modifier: Modifier = Modifier, color: Color = NavigationAccent) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.30f, h * 0.20f)
            lineTo(w * 0.70f, h * 0.50f)
            lineTo(w * 0.30f, h * 0.80f)
            lineTo(w * 0.45f, h * 0.50f)
            close()
        }
        drawPath(path = path, color = color, style = Fill)
    }
}

/** Play / pause transport glyph for the media tile. */
@Composable
private fun MediaTransportGlyph(
    playing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = BoltWhite,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (playing) {
            val barW = w * 0.26f
            val gap = w * 0.20f
            val left = (w - (barW * 2f + gap)) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(left, h * 0.12f),
                size = Size(barW, h * 0.76f),
                cornerRadius = CornerRadius(barW * 0.35f),
                style = Fill,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(left + barW + gap, h * 0.12f),
                size = Size(barW, h * 0.76f),
                cornerRadius = CornerRadius(barW * 0.35f),
                style = Fill,
            )
        } else {
            val path = Path().apply {
                moveTo(w * 0.24f, h * 0.12f)
                lineTo(w * 0.82f, h * 0.50f)
                lineTo(w * 0.24f, h * 0.88f)
                close()
            }
            drawPath(path = path, color = color)
        }
    }
}

/**
 * Crisp, proportional battery glyph with animated green level fill and lightning bolt.
 */
@Composable
private fun BatteryGlyph(percent: Int, modifier: Modifier = Modifier) {
    val fillFraction by androidx.compose.animation.core.animateFloatAsState(
        targetValue = (percent.coerceIn(0, 100)) / 100f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.65f,
            stiffness = 220f,
        ),
        label = "batteryFill",
    )

    Canvas(modifier = modifier) {
        val nubWidth = size.width * 0.08f
        val bodyWidth = size.width - nubWidth
        val strokeWidth = (size.height * 0.12f).coerceAtLeast(1.4f)
        val radius = CornerRadius(size.height * 0.32f)

        drawRoundRect(
            color = BoltWhite,
            topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
            size = Size(bodyWidth - strokeWidth, size.height - strokeWidth),
            cornerRadius = radius,
            style = Stroke(width = strokeWidth),
        )

        val nubHeight = size.height * 0.40f
        drawRoundRect(
            color = BoltWhite,
            topLeft = Offset(bodyWidth, (size.height - nubHeight) / 2f),
            size = Size(nubWidth, nubHeight),
            cornerRadius = CornerRadius(nubWidth * 0.5f),
            style = Fill,
        )

        val inset = strokeWidth * 1.5f
        val innerWidth = (bodyWidth - inset * 2f).coerceAtLeast(0f)
        val innerHeight = (size.height - inset * 2f).coerceAtLeast(0f)
        val fillWidth = (innerWidth * fillFraction).coerceAtLeast(0f)
        if (fillWidth > 0f) {
            drawRoundRect(
                color = ChargingGreen,
                topLeft = Offset(inset, inset),
                size = Size(fillWidth, innerHeight),
                cornerRadius = CornerRadius(innerHeight * 0.25f),
                style = Fill,
            )
        }

        val cx = bodyWidth / 2f
        val bolt = Path().apply {
            moveTo(cx + size.height * 0.14f, size.height * 0.18f)
            lineTo(cx - size.height * 0.18f, size.height * 0.54f)
            lineTo(cx + size.height * 0.02f, size.height * 0.54f)
            lineTo(cx - size.height * 0.10f, size.height * 0.84f)
            lineTo(cx + size.height * 0.20f, size.height * 0.46f)
            lineTo(cx + size.height * 0.00f, size.height * 0.46f)
            close()
        }
        drawPath(bolt, BoltWhite)
    }
}
