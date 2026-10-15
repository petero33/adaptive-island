package dev.personal.adaptiveisland.overlay

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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


import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
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
import dev.personal.adaptiveisland.integrations.MAX_NOTIFICATION_TILES
import dev.personal.adaptiveisland.integrations.MediaSource
import dev.personal.adaptiveisland.integrations.NotificationSource
import dev.personal.adaptiveisland.persistence.FeaturePreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

/** Everything the island needs to decide what to present. */
data class IslandState(
    val showIdlePill: Boolean,
    val activities: List<IslandActivity>,
    val topGapDp: Int = IslandGeometry.TOP_GAP_DP,
    val pillHeightDp: Int = IslandGeometry.PILL_HEIGHT_DP,
    val idleWidthDp: Int = IslandGeometry.IDLE_WIDTH_DP,
    val prioritized: Boolean = false,
    val stackEnabled: Boolean = true,
    val quickActionsEnabled: Boolean = true,
    val reduceEffects: Boolean = false,
    val lowPower: Boolean = false,
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

/** Log tag for the tile "open" path (BAL diagnostics). */
private const val OPEN_TAG = "IslandOpen"

/** At or below this level (and unplugged) the island trims its own effects. */
private const val LOW_BATTERY_PERCENT = 20

/** A neutral label shown in place of real content while privacy masking is on. */
private fun neutralText(category: String?): String = when (category) {
    Notification.CATEGORY_MESSAGE,
    Notification.CATEGORY_EMAIL,
    Notification.CATEGORY_SOCIAL,
    Notification.CATEGORY_CALL,
    Notification.CATEGORY_MISSED_CALL -> "New message"
    else -> "New notification"
}

private val ChargingGreen = Color(0xFF30D158)
private val BluetoothBlue = Color(0xFF4C9AFF)
private val NotificationAccent = Color(0xFF64D2FF)
private val MediaAccent = Color(0xFFFF375F)
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
    // Denser frosted back + 3D top bevel + inner rim for iOS-like liquid glass.
    .background(Color(0xB30F141F))
    .background(
        Brush.verticalGradient(
            colors = listOf(Color(0x5CFFFFFF), Color(0x28FFFFFF), Color(0x0FFFFFFF)),
        ),
    )
    .background(
        Brush.horizontalGradient(
            colors = listOf(Color(0x28FFFFFF), Color(0x00FFFFFF), Color(0x1AFFFFFF)),
        ),
    )
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xD9FFFFFF), Color(0x66FFFFFF), Color(0x1A000000)),
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
    val showNotifications by prefs.enabledFlow("notifications").collectAsState(initial = false)
    val showMedia by prefs.enabledFlow("music_playback").collectAsState(initial = false)
    val prioritize by prefs.enabledFlow("activity_prioritization").collectAsState(initial = true)
    val stackEnabled by prefs.enabledFlow("activity_stack").collectAsState(initial = true)
    val quickActionsEnabled by prefs.enabledFlow("quick_actions").collectAsState(initial = true)
    val lowPowerEnabled by prefs.enabledFlow("low_power_mode").collectAsState(initial = true)
    val privacyShield by prefs.enabledFlow("privacy_shield").collectAsState(initial = false)
    val discreetMode by prefs.enabledFlow("discreet_mode").collectAsState(initial = false)

    // The battery broadcast is sticky and cheap; it also feeds low-power mode.
    val battery by BatterySource.observe(context.applicationContext).collectAsState(
        initial = BatteryState(levelPercent = 0, plugged = false, charging = false, full = false),
    )
    // Optional integrations are suspended while their feature is off, so a
    // disabled island does no Bluetooth work at all.
    val bluetoothFlow = remember(showBluetooth) {
        if (showBluetooth == true) BluetoothSource.observe(context.applicationContext)
        else flowOf(BluetoothState())
    }
    val bluetooth by bluetoothFlow.collectAsState(initial = BluetoothState())
    val powerSave by rememberPowerSave(context.applicationContext)

    val notifications by NotificationSource.notifications.collectAsState()
    val media by MediaSource.nowPlaying.collectAsState()
    val topGapDp by prefs.topGapDpFlow().collectAsState(initial = IslandGeometry.TOP_GAP_DP)
    val pillHeightDp by prefs.pillHeightDpFlow().collectAsState(initial = IslandGeometry.PILL_HEIGHT_DP)
    val idleWidthDp by prefs.idleWidthDpFlow().collectAsState(initial = IslandGeometry.IDLE_WIDTH_DP)

    val lowPower = lowPowerEnabled != false &&
        (powerSave || (battery.levelPercent in 1..LOW_BATTERY_PERCENT && !battery.plugged))

    // Privacy applies at one place — here — so it holds for every activity and
    // never has to be configured per notification source.
    val maskNotifications = privacyShield == true || discreetMode == true
    val maskMedia = discreetMode == true

    val activities = buildList {
        if (showCharging == true && battery.plugged) {
            add(IslandActivity.Charging(battery.levelPercent, battery.charging, battery.full))
        }
        if (showBluetooth == true && bluetooth.connected) {
            add(IslandActivity.Bluetooth(bluetooth.deviceName, bluetooth.batteryPercent))
        }
        if (showMedia == true) {
            media?.let { item ->
                add(
                    IslandActivity.Media(
                        packageName = item.packageName,
                        appLabel = item.appLabel,
                        title = if (maskMedia) item.appLabel else item.title,
                        artist = if (maskMedia) "Playing now" else item.artist,
                        icon = item.icon,
                        isPlaying = item.isPlaying,
                    ),
                )
            }
        }
        if (showNotifications == true) {
            notifications.take(MAX_NOTIFICATION_TILES).forEach { item ->
                // Sensitive alerts stay out of the card entirely while masked.
                if (maskNotifications && item.sensitive) return@forEach
                add(
                    IslandActivity.Notification(
                        key = item.key,
                        packageName = item.packageName,
                        appLabel = item.appLabel,
                        title = if (maskNotifications) item.appLabel else item.title,
                        text = if (maskNotifications) neutralText(item.category) else item.text,
                        icon = item.icon,
                        category = item.category,
                        hasActions = item.hasActions,
                        hasReply = item.hasReply,
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
        prioritized = prioritize != false,
        stackEnabled = stackEnabled != false,
        quickActionsEnabled = quickActionsEnabled != false,
        reduceEffects = lowPower,
        lowPower = lowPower,
    )
}

/** Observes the system power-save state so effects can adapt to it live. */
@Composable
private fun rememberPowerSave(context: Context): androidx.compose.runtime.State<Boolean> {
    val state = remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        state.value = power?.isPowerSaveMode == true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                state.value = power?.isPowerSaveMode == true
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return state
}

/**
 * Main host composable.
 *
 * Collapsed: a black pill over the camera cutout with a compact summary of the
 * newest activity and an (invisible) tap strip just beneath it.
 * Expanded: the pill grows into a black panel holding one separated tile per
 * live activity.
 */
@Composable
fun IslandHost(state: IslandState) {
    MaterialTheme {
        val expanded by IslandExpansion.expanded.collectAsState()
        val hasContent = state.hasContent
        val activities = state.activities
        val selectedId by IslandSelection.selectedId.collectAsState()

        LaunchedEffect(hasContent) {
            if (!hasContent) {
                IslandExpansion.collapse()
                IslandSelection.clear()
            }
        }

        // Remember the last activity the user used, but let a more urgent one
        // take over (an incoming call should surface over paused music).
        LaunchedEffect(activities.map { it.id }, state.prioritized) {
            if (activities.isEmpty()) {
                IslandSelection.clear()
                return@LaunchedEffect
            }
            val current = activities.firstOrNull { it.id == selectedId }
            val top = activities.first()
            val shouldTakeOver = state.prioritized &&
                current != null &&
                ActivityPriority.score(top) > ActivityPriority.score(current)
            if (current == null || shouldTakeOver) {
                IslandSelection.select(top.id)
            }
        }

        val focused = activities.firstOrNull { it.id == selectedId } ?: activities.firstOrNull()
        val showPanel = expanded && hasContent
        val visible = state.showIdlePill || hasContent

        if (!visible) return@MaterialTheme

        val spacing = IslandGeometry.TILE_SPACING_DP.dp
        val collapsedWidth = state.idleWidthDp.dp +
            if (hasContent) IslandGeometry.ACTIVE_EXTRA_WIDTH_DP.dp else 0.dp
        val pillHeight = state.pillHeightDp.dp
        val tapZone = IslandGeometry.TAP_ZONE_HEIGHT_DP.dp

        // The whole stack drops smoothly as the island expands, so the top card
        // clears the status bar / camera cutout.
        val dropDown by animateDpAsState(
            targetValue = if (showPanel) IslandGeometry.EXPANDED_TOP_OFFSET_DP.dp else 0.dp,
            animationSpec = spring(
                dampingRatio = 0.82f,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "expandDrop",
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(dropDown))
            AnimatedContent(
                targetState = showPanel,
                transitionSpec = {
                    fadeIn(tween(190)) togetherWith fadeOut(tween(130))
                },
                label = "islandMode",
            ) { panel ->
                if (panel) {
                    ExpandedPanel(
                        state = state,
                        spacing = spacing,
                        focusedId = focused?.id,
                        reduceEffects = state.reduceEffects,
                    )
                } else {
                    CollapsedPill(
                        state = state,
                        focused = focused,
                        width = collapsedWidth,
                        pillHeight = pillHeight,
                        tapZone = tapZone,
                        reduceEffects = state.reduceEffects,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsedPill(
    state: IslandState,
    focused: IslandActivity?,
    width: Dp,
    pillHeight: Dp,
    tapZone: Dp,
    reduceEffects: Boolean,
) {
    val charging = state.charging
    // A static rim only. A continuous "breathing" glow redrew the overlay at the
    // full display refresh rate for as long as the cable stayed in (~100 fps,
    // measured), which dominated idle CPU. The one-shot chargingIntro below keeps
    // the "plugged in" flourish without any permanent redraw.
    val glow = when {
        charging == null -> 0f
        reduceEffects -> 0.45f
        else -> 0.70f
    }
    val introScale = if (reduceEffects) 1f else chargingIntro(active = charging != null)
    val activities = state.activities
    val index = activities.indexOfFirst { it.id == focused?.id }.coerceAtLeast(0)

    val cycle: (Int) -> Unit = { direction ->
        if (state.stackEnabled && activities.size > 1) {
            val next = (index + direction + activities.size) % activities.size
            IslandSelection.select(activities[next].id)
        }
    }

    Column(
        modifier = Modifier
            .width(width)
            .islandGestures(
                enabled = state.stackEnabled && activities.size > 1,
                onTap = { if (state.hasContent) IslandExpansion.toggle() },
                onSwipe = cycle,
            ),
    ) {
        val shape = RoundedCornerShape(pillHeight / 2)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(pillHeight * introScale)
                .solidIsland(shape)
                .then(
                    if (charging != null) {
                        Modifier.border(1.dp, ChargingGreen.copy(alpha = glow), shape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = focused,
                transitionSpec = {
                    fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                },
                label = "pillActivity",
            ) { activity ->
                if (activity != null) {
                    CollapsedContent(activity, state.notificationCount, activities.size, index)
                }
            }
        }
        if (state.hasContent) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tapZone),
            )
        }
    }
}

/**
 * Lightweight tap / horizontal-swipe detector. Lets the compact pill cycle
 * between activities with a flick, and expand on a plain tap.
 */
private fun Modifier.islandGestures(
    enabled: Boolean,
    onTap: () -> Unit,
    onSwipe: (Int) -> Unit,
): Modifier = if (!enabled) {
    this.pointerInput(Unit) { detectTapGestures { onTap() } }
} else {
    this.pointerInput(Unit) {
        val threshold = 36.dp.toPx()
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false)
                var totalDx = 0f
                var totalDy = 0f
                var up = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUpIgnoreConsumed()) {
                        up = true
                        break
                    }
                    val delta = change.positionChange()
                    totalDx += delta.x
                    totalDy += delta.y
                }
                if (!up) continue
                when {
                    kotlin.math.abs(totalDx) > threshold &&
                        kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy) ->
                        onSwipe(if (totalDx < 0f) 1 else -1)
                    kotlin.math.abs(totalDx) < slop && kotlin.math.abs(totalDy) < slop ->
                        onTap()
                }
            }
        }
    }
}

@Composable
private fun CollapsedContent(
    activity: IslandActivity,
    notificationCount: Int,
    pageCount: Int,
    pageIndex: Int,
) {
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
                PrimaryGlyph(activity, glyphSize)
            }
            Spacer(Modifier.width(IslandGeometry.CUTOUT_CLEARANCE_DP.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (roomyEnough) PrimaryTrailing(activity, notificationCount)
            }
        }
        if (pageCount > 1) {
            PageDots(
                count = pageCount,
                index = pageIndex,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 3.dp),
            )
        }
    }
}

@Composable
private fun PageDots(count: Int, index: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val active = i == index
            Box(
                modifier = Modifier
                    .size(if (active) 5.dp else 3.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (active) 0.85f else 0.35f)),
            )
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

/**
 * A stack of fully independent, content-sized cards: one standalone island per
 * activity, with real gaps between them (the screen shows through). There is
 * deliberately NO shared panel/background holding them together.
 */
@Composable
private fun ExpandedPanel(
    state: IslandState,
    spacing: Dp,
    focusedId: String?,
    reduceEffects: Boolean,
) {
    val activities = state.activities
    // With the activity stack on, the activity you were using is promoted to
    // the top so "tap to expand the one you need" lands on what you expect.
    val ordered = if (state.stackEnabled && focusedId != null) {
        val focused = activities.filter { it.id == focusedId }
        focused + activities.filterNot { it.id == focusedId }
    } else {
        activities
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        ordered.forEachIndexed { index, activity ->
            TileEntrance(index = index, activityId = activity.id, reduceEffects = reduceEffects) {
                when (activity) {
                    is IslandActivity.Media -> MediaTile(activity, index, reduceEffects)
                    is IslandActivity.Notification -> NotificationTile(
                        activity = activity,
                        index = index,
                        quickActionsEnabled = state.quickActionsEnabled,
                        reduceEffects = reduceEffects,
                    )
                    else -> ActivityTile(activity, index, reduceEffects)
                }
            }
        }
    }
}

@Composable
private fun TileEntrance(
    index: Int,
    activityId: String,
    reduceEffects: Boolean,
    content: @Composable () -> Unit,
) {
    var revealed by remember(activityId) { mutableStateOf(reduceEffects) }
    LaunchedEffect(activityId, reduceEffects) {
        if (reduceEffects) {
            revealed = true
            return@LaunchedEffect
        }
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
private fun MediaTile(activity: IslandActivity.Media, index: Int, reduceEffects: Boolean) {
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
private fun NotificationTile(
    activity: IslandActivity.Notification,
    index: Int,
    quickActionsEnabled: Boolean,
    reduceEffects: Boolean,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(IslandGeometry.TILE_RADIUS_DP.dp)
    val actions = if (quickActionsEnabled) {
        NotificationSource.actionsFor(activity.key).take(2)
    } else {
        emptyList()
    }

    val launchable = remember(activity.packageName) {
        context.packageManager.getLaunchIntentForPackage(activity.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // Android 15/16 background-activity-launch rules block sending another app's
    // content PendingIntent from a third-party overlay (it silently no-ops when
    // pi.send() returns true). Our own startActivity is exempt because this uid
    // owns a visible non-app window and runs a foreground service, so prefer it
    // and keep the deep-link PendingIntent only as a fallback.
    val open: () -> Unit = {
        val launched = launchable?.let { intent ->
            runCatching { context.startActivity(intent) }
                .onFailure { Log.w(OPEN_TAG, "startActivity(${activity.packageName}) failed", it) }
                .isSuccess
        } ?: false
        if (!launched && !NotificationSource.open(activity.key)) {
            Log.w(OPEN_TAG, "no launch or content intent for ${activity.packageName}")
        }
        IslandSelection.select(activity.id)
        IslandExpansion.collapse()
    }

    // Tapping a RemoteInput ("Reply") chip reveals a small inline text field;
    // other chips fire their action immediately.
    var replyIndex by remember(activity.key) { mutableStateOf<Int?>(null) }
    var replyText by remember(activity.key) { mutableStateOf("") }

    val sheen = rememberSheen(index, reduceEffects)

    Column(
        modifier = Modifier
            .widthIn(min = 190.dp, max = 300.dp)
            .glassIsland(shape)
            .sheenOverlay(sheen)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(
            modifier = Modifier.clickable(onClick = open),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(bitmap = activity.icon, size = 30.dp, fallbackColor = NotificationAccent)
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
                Text(
                    text = activity.text.ifBlank { activity.appLabel },
                    color = MutedText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (quickActionsEnabled) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickActionChip(
                    label = "Open",
                    accent = NotificationAccent,
                    onClick = open,
                )
                actions.forEachIndexed { actionIndex, action ->
                    QuickActionChip(
                        label = action.title,
                        accent = NotificationAccent,
                        onClick = {
                            NotificationSource.fireAction(context, activity.key, actionIndex)
                            IslandExpansion.collapse()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionChip(
    label: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun rememberSheen(index: Int, reduceEffects: Boolean = false): Animatable<Float, *> {
    val sheen = remember { Animatable(if (reduceEffects) 1f else 0f) }
    LaunchedEffect(reduceEffects) {
        if (reduceEffects) return@LaunchedEffect
        // Subtler, faster sheen that runs once per appearance.
        delay(index * 40L)
        sheen.animateTo(1f, tween(durationMillis = 500, easing = FastOutSlowInEasing))
    }
    return sheen
}

private fun Modifier.sheenOverlay(sheen: Animatable<Float, *>): Modifier = drawWithContent {
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

@Composable
private fun ActivityTile(activity: IslandActivity, index: Int, reduceEffects: Boolean = false) {
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

    // One-shot liquid-glass sheen that sweeps across the card as it appears.
    val sheen = remember { Animatable(if (reduceEffects) 1f else 0f) }
    LaunchedEffect(reduceEffects) {
        if (reduceEffects) return@LaunchedEffect
        delay(index * 40L)
        sheen.animateTo(1f, tween(durationMillis = 500, easing = FastOutSlowInEasing))
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
    }
}

private fun IslandActivity.tileTitle(): String = when (this) {
    is IslandActivity.Charging -> "Charging"
    is IslandActivity.Bluetooth -> deviceName ?: "Bluetooth audio"
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
    is IslandActivity.Notification -> text.ifBlank { appLabel }
    is IslandActivity.Media -> artist?.takeIf { it.isNotBlank() } ?: appLabel
}

private fun IslandActivity.tileTrailing(): String? = when (this) {
    is IslandActivity.Charging -> "$level%"
    is IslandActivity.Bluetooth -> batteryPercent?.let { "$it%" } ?: "On"
    is IslandActivity.Notification -> null
    is IslandActivity.Media -> null
}

private fun IslandActivity.accent(): Color = when (this) {
    is IslandActivity.Charging -> ChargingGreen
    is IslandActivity.Bluetooth -> BluetoothBlue
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
