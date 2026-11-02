package com.c17.notify.replace.hook.games

import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.c17.notify.replace.diagnostics.DiagnosticEvent
import com.c17.notify.replace.diagnostics.DiagnosticLevel
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.diagnostics.OccurrencePolicy
import com.c17.notify.replace.hook.HookRegistrar
import java.util.WeakHashMap

/**
 * Keeps themed icons on the [ImageView]s the game assistant repaints after bind.
 *
 * Two host habits defeat a single `setImageDrawable` at bind time:
 *
 * - the app icons are loaded through Glide, whose disk cache replays the pre-module picture a few
 *   frames after the bind and replaces whatever was written first;
 * - the message-box mark is written by the host *after* the row is bound, from a coroutine.
 *
 * The previous answer was a ladder of delayed re-checks. It worked, but it lost a race by design:
 * Glide's cached result landed *before* the first re-check tick, so a fast scroll showed each cell
 * in its original artwork for a few hundred milliseconds before the theme icon snapped back. And
 * while the ladder itself was cheap, the visible rewrites kept triggering row redraws.
 *
 * The answer now is interception: one process-wide hook on `ImageView#setImageDrawable`. Views
 * that [watch] was called on are remembered with their expected drawable; while the watch is
 * alive, any write that is not the expected drawable is swapped for it *inside the hook*, so the
 * original artwork never lands at all. The per-call fast path is one `WeakHashMap` lookup plus one
 * identity compare — cheaper than the ladder and without the flash.
 */
internal object GameIconWatchdog {

    private class Entry(val expected: Drawable)

    private val watched = WeakHashMap<ImageView, Entry>()

    /**
     * Per-view bind generation. Every claim ([watch]) or drop ([release]) invalidates background
     * resolutions started by an earlier bind of the same recycled view, so a slow resolve of the
     * previous package can never paint its icon onto a cell now showing another one.
     */
    private val generations = WeakHashMap<ImageView, java.util.concurrent.atomic.AtomicInteger>()

    private var installed = false

    private lateinit var diagnostics: Diagnostics

    /** Installs the one-time interception hook. Harmless if the method lookup fails: watch() then simply writes once and the host may win, as before. */
    fun install(hooks: HookRegistrar, diagnostics: Diagnostics) {
        if (installed) return
        this.diagnostics = diagnostics
        val method = runCatching {
            ImageView::class.java.getDeclaredMethod("setImageDrawable", Drawable::class.java)
        }.getOrNull() ?: return
        installed = true
        hooks.install(method, "games.image_view_guard") { chain ->
            val view = chain.instanceOrNull as? ImageView
            val incoming = chain.args.getOrNull(0) as? Drawable
            val expected = if (view != null) replacementFor(view, incoming) else null
            if (view != null && expected != null && incoming != null) {
                traceIntercept(view, incoming)
                val newArgs = chain.args.toTypedArray().also { it[0] = expected }
                chain.proceed(newArgs)
            } else {
                chain.proceed()
            }
        }
    }

    private fun traceIntercept(view: ImageView, incoming: Drawable) {
        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.GameAssistantWatchdog,
            message = "图标看门狗：拦下宿主写入，换回主题图标",
            attributes = mapOf(
                "stage" to "intercept",
                "view" to view.javaClass.name,
                "incoming" to incoming.javaClass.name,
            ),
            occurrence = OccurrencePolicy.RateLimited("games:watchdog_intercept", 2_000L),
        )
    }

    /**
     * Claims [icon] for [drawable]: writes it once and keeps every later host write pointing at it
     * indefinitely. The claim lives until the view is rebound ([watch] replaces it, [release] drops
     * it). No time window: an expiring claim let the host's next Glide write land the original
     * artwork once the user stopped scrolling for a few seconds.
     */
    @Suppress("UNUSED_PARAMETER")
    fun watch(icon: ImageView, drawable: Drawable, windowMillis: Long) {
        // Register first: the write below goes through the interception hook itself, and the fresh
        // entry must already be in place or a rebinding cell would briefly swap back to the old
        // claim. Bumping the generation also cancels any pending background resolution of an
        // earlier bind of this recycled view.
        synchronized(watched) {
            generationOf(icon).incrementAndGet()
            watched[icon] = Entry(drawable)
        }
        icon.setImageDrawable(drawable)
        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.GameAssistantWatchdog,
            message = "图标看门狗：绑定后接管图标视图",
            attributes = mapOf(
                "stage" to "watch",
                "view" to icon.javaClass.name,
                "drawable" to drawable.javaClass.name,
            ),
            occurrence = OccurrencePolicy.RateLimited("games:watchdog_watch", 2_000L),
        )
    }

    /**
     * Drops any claim on [icon] so host writes land untouched, and bumps the generation so a
     * background resolution started by the previous bind can no longer claim the view. Called when
     * a recycled cell binds to a package whose themed artwork is not cached yet.
     */
    fun release(icon: ImageView) {
        synchronized(watched) {
            generationOf(icon).incrementAndGet()
            watched.remove(icon)
        }
    }

    /** Marks the start of a background resolution; returns the generation token to fulfill with. */
    fun reserve(icon: ImageView): Int = synchronized(watched) { generationOf(icon).incrementAndGet() }

    /** Whether [generation] is still the latest bind generation of [icon]. */
    fun isCurrent(icon: ImageView, generation: Int): Boolean =
        synchronized(watched) { generations[icon]?.get() == generation }

    private fun generationOf(icon: ImageView): java.util.concurrent.atomic.AtomicInteger =
        generations.getOrPut(icon) { java.util.concurrent.atomic.AtomicInteger() }

    /** The drawable to write instead of [incoming], or null to let the host's write through. */
    private fun replacementFor(icon: ImageView, incoming: Drawable?): Drawable? {
        if (incoming == null) return null
        val entry = synchronized(watched) { watched[icon] } ?: return null
        // No expiry: the claim lives until the view is rebound (watch/release replaces it).
        // The old deadline-based window caused the icon to revert to native after a few seconds
        // of sitting still — the host's next Glide write found no claim and landed untouched.
        return if (incoming === entry.expected) null else entry.expected
    }
}
