package com.getfirepit.app.privacy

import android.view.Window
import android.view.WindowManager
import java.util.WeakHashMap

/**
 * Who needs the window kept out of screenshots, screen recordings and the
 * Recents snapshot right now.
 *
 * More than one thing asks — the setting, and the invite screen, which always
 * does — and the flag is one bit, so whoever finishes last is the one allowed
 * to clear it. Main thread only, like the window itself.
 */
object SecureWindow {

    // Weak, so a window whose activity is gone is not kept alive by being secure.
    private val holders = WeakHashMap<Window, MutableSet<Any>>()

    fun hold(window: Window, holder: Any) {
        holders.getOrPut(window) { mutableSetOf() } += holder
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window, holder: Any) {
        val remaining = holders[window] ?: return
        remaining -= holder
        if (remaining.isEmpty()) {
            holders.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
