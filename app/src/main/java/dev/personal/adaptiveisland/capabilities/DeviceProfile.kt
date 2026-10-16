package dev.personal.adaptiveisland.capabilities

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Immutable snapshot of the host device and the permissions we currently hold.
 * Populated once at startup (and refreshed when permissions change).
 */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidRelease: String,
    val sdkInt: Int,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val densityDpi: Int,
    val density: Float,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val smallestWidthDp: Int,
    val cutoutSummary: String,
    val hasOverlayPermission: Boolean,
) {
    val sdkLabel: String get() = "Android $androidRelease (API $sdkInt)"
    val hardwareLabel: String get() = "$manufacturer $model"
    val resolutionLabel: String get() = "$screenWidthPx x $screenHeightPx px"
    val densityLabel: String get() = "${densityDpi} dpi (${"%.2f".format(density)}x)"
    val dpLabel: String get() = "$screenWidthDp x $screenHeightDp dp"
    val smallestWidthLabel: String get() = "$smallestWidthDp dp"

    val summary: String
        get() = buildString {
            appendLine("Device: $hardwareLabel ($device)")
            appendLine("System: $sdkLabel")
            appendLine("Resolution: $resolutionLabel")
            appendLine("Density: $densityLabel")
            appendLine("Size (dp): $dpLabel, smallest $smallestWidthLabel")
            appendLine("Cutout: $cutoutSummary")
            append("Overlay permission: ${if (hasOverlayPermission) "granted" else "not granted"}")
        }
}

object DeviceProfileReader {

    fun read(context: Context, cutoutSummary: String = "pending"): DeviceProfile {
        val metrics = context.resources.displayMetrics
        val config = context.resources.configuration
        return DeviceProfile(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            device = Build.DEVICE,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            screenWidthPx = metrics.widthPixels,
            screenHeightPx = metrics.heightPixels,
            densityDpi = metrics.densityDpi,
            density = metrics.density,
            screenWidthDp = config.screenWidthDp,
            screenHeightDp = config.screenHeightDp,
            smallestWidthDp = config.smallestScreenWidthDp,
            cutoutSummary = cutoutSummary,
            hasOverlayPermission = Settings.canDrawOverlays(context),
        )
    }

    /**
     * Reads the display cutout geometry from the activity window. Must be called
     * after the window has been attached (e.g. posted to the decor view).
     */
    fun readCutout(activity: Activity): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "none (pre-API 28)"
        val insets = activity.window.decorView.rootWindowInsets ?: return "unavailable"
        val cutout = insets.displayCutout ?: return "none"
        val rects = cutout.boundingRects.joinToString(" ; ") {
            "l=${it.left} t=${it.top} r=${it.right} b=${it.bottom}"
        }
        return "safeTop=${cutout.safeInsetTop} safeBottom=${cutout.safeInsetBottom} [$rects]"
    }
}
