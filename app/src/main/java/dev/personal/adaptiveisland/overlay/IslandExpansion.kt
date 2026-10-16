package dev.personal.adaptiveisland.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared, process-wide expansion state of the island.
 *
 * Kept outside the Compose tree so the overlay window (which watches for
 * outside touches) can collapse the island without recomposing the whole host.
 */
object IslandExpansion {

    private val _expanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()

    fun set(value: Boolean) {
        if (_expanded.value != value) _expanded.value = value
    }

    fun toggle() = set(!_expanded.value)

    fun expand() = set(true)

    fun collapse() = set(false)
}
