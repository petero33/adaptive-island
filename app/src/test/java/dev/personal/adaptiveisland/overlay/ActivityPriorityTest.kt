package dev.personal.adaptiveisland.overlay

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ActivityPriorityTest {

    @Test
    fun `score ranks calls highest`() {
        assertEquals(100, ActivityPriority.CALL)
        assertEquals(90, ActivityPriority.TIMER)
        assertEquals(80, ActivityPriority.NAVIGATION)
        assertEquals(70, ActivityPriority.MEDIA)
        assertEquals(50, ActivityPriority.ACTIONABLE)
        assertEquals(30, ActivityPriority.NOTIFICATION)
        assertEquals(20, ActivityPriority.CHARGING)
        assertEquals(10, ActivityPriority.BLUETOOTH)
    }

    @Test
    fun `score returns expected values per activity type`() {
        assertEquals(ActivityPriority.CHARGING, ActivityPriority.score(IslandActivity.Charging(50, true, false)))
        assertEquals(ActivityPriority.BLUETOOTH, ActivityPriority.score(IslandActivity.Bluetooth(null, null)))
        assertEquals(ActivityPriority.MEDIA, ActivityPriority.score(IslandActivity.Media("pkg", "app", "title", null, null, true)))
    }

    @Test
    fun `score ranks notification by category`() {
        // Plain notification (no category match -> NOTIFICATION or ACTIONABLE)
        assertEquals(ActivityPriority.NOTIFICATION, ActivityPriority.score(IslandActivity.Notification("k", "pkg", "app", "t", "x", null, null, false)))

        // Action-enabled plain notification -> ACTIONABLE
        assertEquals(ActivityPriority.ACTIONABLE, ActivityPriority.score(IslandActivity.Notification("k", "pkg", "app", "t", "x", null, null, true)))

        // Call category -> CALL
        assertEquals(ActivityPriority.CALL, ActivityPriority.score(IslandActivity.Notification("k", "pkg", "app", "t", "x", null, "call", false)))

        // Alarm category -> TIMER
        assertEquals(ActivityPriority.TIMER, ActivityPriority.score(IslandActivity.Notification("k", "pkg", "app", "t", "x", null, "alarm", false)))

        // Navigation category -> NAVIGATION
        assertEquals(ActivityPriority.NAVIGATION, ActivityPriority.score(IslandActivity.Notification("k", "pkg", "app", "t", "x", null, "navigation", false)))
    }
}
