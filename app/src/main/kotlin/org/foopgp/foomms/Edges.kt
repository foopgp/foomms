package org.foopgp.foomms

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets

/**
 * Room for the system's bars, made by the page itself: Android 16 puts an
 * application that targets it edge to edge and no longer honours the way
 * out. The window then starts at the top of the screen, and the content with it -- under the clock and under our own bar --
 * and ends under the gesture bar (seen in foodjis first).
 *
 * One place for every page: each builds its
 * view in code, and all of them sit in the window's content frame, which is
 * what is padded here.
 *
 * Only where the room is really missing: the content is told of the bars
 * only when the window does not make room for them itself. Where it does --
 * before Android 15, or where the way out is still honoured -- they come as
 * nothing, and nothing moves.
 */
object Edges {

    fun fit(activity: Activity) {
        if (Build.VERSION.SDK_INT < 30) return
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            // With the window edge to edge, the keyboard no longer shrinks it
            // either: what it covers is ours to leave free.
            val ime = insets.getInsets(WindowInsets.Type.ime())
            // What arrives at the top already counts our bar under the
            // clock: the window's frame adds it before passing the insets on.
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        content.requestApplyInsets()
    }
}
