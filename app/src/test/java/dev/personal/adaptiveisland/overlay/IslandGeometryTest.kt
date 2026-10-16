package dev.personal.adaptiveisland.overlay

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class IslandGeometryTest {

    @Test
    fun `calibrated geometry constants match documented defaults`() {
        assertEquals(4, IslandGeometry.TOP_GAP_DP)
        assertEquals(116, IslandGeometry.IDLE_WIDTH_DP)
        assertEquals(32, IslandGeometry.PILL_HEIGHT_DP)
        assertEquals(140, IslandGeometry.CHARGING_WIDTH_DP)
        assertEquals(34, IslandGeometry.CHARGING_HEIGHT_DP)
        assertEquals(24, IslandGeometry.ACTIVE_EXTRA_WIDTH_DP)
        assertEquals(48, IslandGeometry.CUTOUT_CLEARANCE_DP)
        assertEquals(320, IslandGeometry.EXPANDED_WIDTH_DP)
        assertEquals(46, IslandGeometry.TILE_HEIGHT_DP)
        assertEquals(6, IslandGeometry.TILE_SPACING_DP)
        assertEquals(22, IslandGeometry.TILE_RADIUS_DP)
        assertEquals(8, IslandGeometry.PANEL_PADDING_DP)
        assertEquals(30, IslandGeometry.PANEL_RADIUS_DP)
        assertEquals(44, IslandGeometry.EXPANDED_TOP_OFFSET_DP)
        assertEquals(30, IslandGeometry.TAP_ZONE_HEIGHT_DP)
        assertEquals(140, IslandGeometry.COMPACT_WIDTH_DP)
        assertEquals(175, IslandGeometry.EXPANDED_HEIGHT_DP)
    }
}
