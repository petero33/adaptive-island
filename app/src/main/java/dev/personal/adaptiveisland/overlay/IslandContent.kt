package dev.personal.adaptiveisland.overlay

import android.content.Intent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    val showNotifications by prefs.enabledFlow("notifications").collectAsState(initial = false)
    val showMedia by prefs.enabledFlow("music_playback").collectAsState(initial = false)
    val battery by BatterySource.observe(context.applicationContext).collectAsState(
        initial = BatteryState(levelPercent = 0, plugged = false, charging = false, full = false),
    )
    val bluetooth by BluetoothSource.observe(context.applicationContext).collectAsState(
        initial = BluetoothState(),
    )
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
                    ),
                )
            }
        }
    }

    return IslandState(
        showIdlePill = showIdlePill ?: true,
        activities = activities,
        topGapDp = topGapDp,
        pillHeightDp = pillHeightDp,
        idleWidthDp = idleWidthDp,
    )
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
        LaunchedEffect(hasContent) { if (!hasContent) IslandExpansion.collapse() }
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
                    )
                } else {
                    CollapsedPill(
                        state = state,
                        width = collapsedWidth,
                        pillHeight = pillHeight,
                        tapZone = tapZone,
                    )
                }
            }
        }
    }
}

@Composable
private fun CollapsedPill(
    state: IslandState,
    width: Dp,
    pillHeight: Dp,
    tapZone: Dp,
) {
    val charging = state.charging
    val glow = if (charging != null) chargingGlow() else 0f
    val introScale = chargingIntro(active = charging != null)
    Column(modifier = Modifier.width(width)) {
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
            CollapsedContent(state)
        }
        if (state.hasContent) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tapZone)
                    .pointerInput(Unit) {
                        detectTapGestures { IslandExpansion.toggle() }
                    },
            )
        }
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
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        state.activities.forEachIndexed { index, activity ->
            TileEntrance(index = index) {
                ActivityTile(activity, index)
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
