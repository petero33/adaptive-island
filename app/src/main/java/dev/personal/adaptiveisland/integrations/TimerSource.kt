package dev.personal.adaptiveisland.integrations

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

/** Snapshot of the timer / stopwatch state. */
data class TimerState(
    /** Elapsed milliseconds. For stopwatch: time running. For timer: time remaining. */
    val elapsedMillis: Long = 0,
    /** True when the timer is running. */
    val running: Boolean = false,
    /** True when this is a countdown timer (decreasing). False for stopwatch (increasing). */
    val countdown: Boolean = false,
    /** Target time in millis for a countdown timer (ignored for stopwatch). */
    val targetMillis: Long = 0,
    /** True when a countdown timer has reached zero. */
    val finished: Boolean = false,
)

/**
 * Controllable timer / stopwatch source.
 *
 * Use [start] / [stop] / [reset] / [toggle] to drive it from the island UI.
 * [observe] emits [TimerState] updates every 100 ms while running.
 */
object TimerSource {

    private val _state: MutableStateFlow<TimerState> = MutableStateFlow(TimerState())
    val state: Flow<TimerState> = _state

    private var tickJob: Job? = null
    private var startedAt: Long = 0

    private fun nowMillis(): Long = System.currentTimeMillis()

    /** Start (or resume) the timer / stopwatch. */
    fun start() {
        stopTick()
        val current = _state.value
        val now = nowMillis()
        if (!current.running) {
            startedAt = now - (if (current.countdown) current.targetMillis - current.elapsedMillis else current.elapsedMillis)
        }
        _state.value = current.copy(running = true, finished = false)
        startTick()
    }

    /** Stop (pause) the timer / stopwatch. */
    fun stop() {
        stopTick()
        val current = _state.value
        if (current.running) {
            val now = nowMillis()
            val elapsed = if (current.countdown) current.targetMillis - (now - startedAt) else now - startedAt
            _state.value = current.copy(
                running = false,
                elapsedMillis = max(0L, elapsed),
            )
        }
    }

    /** Reset to initial state. */
    fun reset(targetMillis: Long = 0, countdown: Boolean = false) {
        stopTick()
        _state.value = TimerState(
            elapsedMillis = if (countdown) targetMillis else 0,
            running = false,
            countdown = countdown,
            targetMillis = targetMillis,
            finished = false,
        )
        startedAt = nowMillis()
    }

    /** Toggle running state. */
    fun toggle() {
        if (_state.value.running) stop() else start()
    }

    /** Set a countdown target and start. */
    fun startCountdown(targetMillis: Long) {
        reset(targetMillis, countdown = true)
        start()
    }

    private fun startTick() {
        tickJob?.cancel()
        tickJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                delay(100L)
                val current = _state.value
                if (!current.running) break
                val now = nowMillis()
                val rawElapsed = now - startedAt
                val elapsed = if (current.countdown) {
                    val remaining = current.targetMillis - rawElapsed
                    if (remaining <= 0) {
                        _state.value = current.copy(running = false, elapsedMillis = 0, finished = true)
                        break
                    }
                    remaining
                } else {
                    rawElapsed
                }
                _state.value = current.copy(elapsedMillis = max(0L, elapsed))
            }
        }
    }

    private fun stopTick() {
        tickJob?.cancel()
        tickJob = null
    }

    /** Observe timer state. Emits every 100 ms while running. */
    fun observe(): Flow<TimerState> = state.distinctUntilChanged().conflate()
}
