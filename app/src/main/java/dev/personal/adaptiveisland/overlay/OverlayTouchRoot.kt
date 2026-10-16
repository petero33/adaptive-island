package dev.personal.adaptiveisland.overlay

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * Root container for the island overlay. It listens for touches that land
 * *outside* the window (enabled via
 * [android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH]) so the
 * expanded panel can collapse when the user taps the app behind it.
 */
class OverlayTouchRoot(context: Context) : FrameLayout(context) {

    var onOutsideTouch: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null && ev.action == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch?.invoke()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
