package com.c17.notify.replace.hook.games

import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import java.util.WeakHashMap

/**
 * Keeps one themed icon on an [ImageView] the game assistant paints over.
 *
 * Two host habits defeat a single `setImageDrawable`:
 *
 * - the app icons are loaded through Glide (`Lx1/d` → `ExternalApplicationManager.u`), so the
 *   request's result lands a few frames after the bind and replaces whatever was written first —
 *   and Glide's own disk cache replays the pre-module picture without ever calling the loader;
 * - the message-box mark is written by the host *after* the row is bound, from a coroutine.
 *
 * This used to be an `OnPreDrawListener` per bound view, which is what made the app-icon strip
 * stutter while scrolling: `ImageView#getViewTreeObserver` returns the *window* observer, so every
 * guard ever armed is called on every frame, and `take()` adds/removes one per bound cell — each of
 * those makes the observer copy its listener array on the UI thread. A scrolling list therefore paid
 * "cells on screen × frames" callbacks plus one array copy per bind.
 *
 * A short ladder of delayed re-checks replaces it: the icon is written immediately, then compared
 * against the expected drawable a handful of times over the next few seconds. That is a couple of
 * identity compares per bind instead of a callback per frame, and it still covers Glide's async
 * result plus the host's own coroutine.
 */
internal class ViewIconTakeover(
    private val icon: ImageView,
    private val expected: Drawable,
    private val windowMillis: Long,
) {

    private var deadline = 0L
    private var applied = 0
    private var nextStep = 0
    private var stopped = false

    /** Runs the ladder; only on the UI thread, which is where the bind happens. */
    fun start() {
        val now = SystemClock.elapsedRealtime()
        deadline = now + windowMillis
        icon.setImageDrawable(expected)
        step()
    }

    fun stop() {
        if (stopped) return
        stopped = true
        icon.removeCallbacks(stepRunnable)
    }

    private val stepRunnable = Runnable { step() }

    private fun step() {
        if (stopped) return
        val now = SystemClock.elapsedRealtime()
        if (!icon.isAttachedToWindow || now > deadline) {
            stopped = true
            return
        }
        if (icon.drawable !== expected) {
            if (applied >= MAX_REAPPLIES) {
                stopped = true
                return
            }
            applied++
            icon.setImageDrawable(expected)
        }
        // The host may still be decoding: keep watching until the ladder is exhausted. Past the
        // window the guard is gone anyway, and the next bind re-arms it.
        if (nextStep >= STEP_DELAYS_MILLIS.size) {
            stopped = true
            return
        }
        val delay = STEP_DELAYS_MILLIS[nextStep++]
        if (now + delay > deadline) {
            stopped = true
            return
        }
        icon.postDelayed(stepRunnable, delay)
    }

    private companion object {
        /**
         * Cumulative ≈ 7.8 s, which outlasts both Glide (a few frames to a couple of hundred
         * milliseconds for a cached icon) and the message box's own coroutine. Every step is one
         * `isAttachedToWindow` read plus one identity compare, so the tail costs nothing.
         */
        val STEP_DELAYS_MILLIS = longArrayOf(32L, 96L, 240L, 480L, 1000L, 2000L, 4000L)

        /** Upper bound on writes, so a host loop cannot turn the ladder into a repaint storm. */
        const val MAX_REAPPLIES = 24
    }
}

/**
 * One guard per [ImageView]. Rebinding a recycled row replaces the previous guard instead of
 * stacking a second one on the same view.
 */
internal object GameIconTakeovers {

    private val active = WeakHashMap<ImageView, ViewIconTakeover>()

    fun take(icon: ImageView, drawable: Drawable, windowMillis: Long = DEFAULT_WINDOW_MILLIS) {
        // Single lookup + single write: a WeakHashMap walk runs `expungeStaleEntries` on every
        // access, and a scrolling list calls this once per bound cell.
        val takeover = ViewIconTakeover(icon, drawable, windowMillis)
        val previous = synchronized(active) { active.put(icon, takeover) }
        previous?.stop()
        takeover.start()
    }

    private const val DEFAULT_WINDOW_MILLIS = 5_000L
}
