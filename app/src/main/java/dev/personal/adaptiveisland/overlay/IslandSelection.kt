package dev.personal.adaptiveisland.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers which activity the user last focused inside the island, so the
 * compact pill can keep showing it and switching back doesn't start over.
 *
 * Kept process-wide (like [IslandExpansion]) so it survives recomposition and
 * window re-creation while the overlay service lives.
 */
object IslandSelection {

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    fun select(id: String?) {
        if (_selectedId.value != id) _selectedId.value = id
    }

    fun clear() = select(null)
}
