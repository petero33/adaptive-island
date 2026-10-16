package dev.personal.adaptiveisland.overlay

import android.content.Context

/**
 * Positions and dimensions for the island window.
 *
 * Calibrated for punch-hole camera displays (such as TECNO SPARK 50):
 * - Screen density: 280 dpi (1.75x)
 * - Camera cutout reaches ~72 px from the top edge.
 * - The capsule is anchored near the very top (4 dp gap) so it blends with the
 *   camera cutout, producing the seamless "hole punch" look.
 */
object IslandGeometry {

    /** Gap between the top screen edge and the capsule. */
    const val TOP_GAP_DP = 4

    /** Idle pill. */
    const val IDLE_WIDTH_DP = 116
    const val PILL_HEIGHT_DP = 40

    /** Charging capsule: slightly wider so the glyph and percentage have room. */
    const val CHARGING_WIDTH_DP = 140
    const val CHARGING_HEIGHT_DP = 34

    /** Extra width the pill gains while a function (charging) is active. */
    const val ACTIVE_EXTRA_WIDTH_DP = 24

    /** Horizontal clearance reserved for the camera punch-hole. */
    const val CUTOUT_CLEARANCE_DP = 48

    /** Expanded panel with one tile per live activity. HiOS clamps overlay
     *  windows to ~320 dp wide, so the panel tops out there. */
    const val EXPANDED_WIDTH_DP = 320
    const val TILE_HEIGHT_DP = 46
    const val TILE_SPACING_DP = 6
    const val TILE_RADIUS_DP = 22
    const val PANEL_PADDING_DP = 8
    const val PANEL_RADIUS_DP = 30

    /** How far the expanded stack slides down so the top card clears the
     *  status bar (battery %, network speed, camera cutout). */
    const val EXPANDED_TOP_OFFSET_DP = 44

    /** Transparent, tappable strip below the collapsed pill (see OverlayTouchRoot). */
    const val TAP_ZONE_HEIGHT_DP = 30

    // Retained for the control-centre geometry sliders / compatibility.
    const val COMPACT_WIDTH_DP = CHARGING_WIDTH_DP
    const val EXPANDED_HEIGHT_DP = 175

    /** Top offset in pixels where the capsule begins. */
    fun topOffsetPx(context: Context, topGapDp: Int = TOP_GAP_DP): Int = dp(context, topGapDp)

    /** Alias used by the overlay service. */
    fun topGapPx(context: Context, topGapDp: Int = TOP_GAP_DP): Int = dp(context, topGapDp)

    /** Helper to convert dp to pixels. */
    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
