package com.c17.notify.replace.hook.games

import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewTreeObserver
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

    /**
     * Chrome the claim took off a view, kept so [release] can put it back.
     *
     * The dock lays a pale plate *and* a 1 dp circle outline on each app icon slot; the skin layer
     * then re-applies the outline after the cell is bound. Themed artwork is drawn over that chrome
     * rather than replacing it, so a full-bleed icon hides it while anything smaller than the slot
     * shows it: as pale corners behind a rounded square (reported 2026-11-21 "四个角的颜色和别处不
     * 一样"), as a ring floating around the icon once the artwork became a circle (2026-11-22
     * "能看到图标外围有个圈"). Where the chrome lives is not fixed: the icon view's own background on
     * some builds, its foreground or an icon-sized wrapper on others — hence a list of views.
     */
    private class Plate(val view: View, val background: Drawable?, val foreground: Drawable? = null)

    private class Entry(val expected: Drawable, val plates: List<Plate> = emptyList())

    private val watched = WeakHashMap<ImageView, Entry>()

    /**
     * Per-view bind generation. Every claim ([watch]) or drop ([release]) invalidates background
     * resolutions started by an earlier bind of the same recycled view, so a slow resolve of the
     * previous package can never paint its icon onto a cell now showing another one.
     */
    private val generations = WeakHashMap<ImageView, java.util.concurrent.atomic.AtomicInteger>()

    private var installed = false

    /** How many ancestors above the icon view are examined for an icon-sized plate. */
    private const val PLATE_ANCESTOR_DEPTH = 3

    /**
     * An ancestor counts as "the cell" only while it is no bigger than this multiple of the icon —
     * a row or the panel is far larger, and stripping its background would change the app.
     */
    private const val PLATE_MAX_RATIO = 1.35f

    private lateinit var diagnostics: Diagnostics

    /** Installs interception hooks on every path the host can use to paint an icon. */
    fun install(hooks: HookRegistrar, diagnostics: Diagnostics, classLoader: ClassLoader) {
        if (installed) return
        this.diagnostics = diagnostics
        installed = true

        // Path 1: ImageView.setImageDrawable — the standard Glide target. COUIRoundImageView may
        // override it and still call super, so this hook alone is not enough.
        hookDrawableMethod(hooks, ImageView::class.java, "games.image_view_guard")

        // Path 2: COUIRoundImageView.setImageDrawable — if COUI overrides the method and keeps its
        // own internal drawable field for onDraw, the ImageView-level hook swaps super's argument
        // but COUI's field still holds the original. Hooking the override itself replaces the
        // argument before COUI's own logic ever sees it.
        val couiClass = runCatching {
            classLoader.loadClass("com.coui.appcompat.imageview.COUIRoundImageView")
        }.getOrNull()
        if (couiClass != null) {
            hookDrawableMethod(hooks, couiClass, "games.coui_drawable_guard")
            // Path 3: COUIRoundImageView.setImageBitmap — Glide can deliver through this method
            // instead of setImageDrawable; if COUI overrides it without calling super, neither of
            // the two hooks above sees the write.
            hookBitmapMethod(hooks, couiClass, "games.coui_bitmap_guard")
        }
    }

    /** Hooks `setImageDrawable(Drawable)` on [owner]: swaps the argument when a claim exists. */
    private fun hookDrawableMethod(hooks: HookRegistrar, owner: Class<*>, label: String) {
        val method = runCatching {
            owner.getDeclaredMethod("setImageDrawable", Drawable::class.java)
        }.getOrNull() ?: return
        hooks.install(method, label) { chain ->
            val view = chain.instanceOrNull as? ImageView
            val incoming = chain.args.getOrNull(0) as? Drawable
            val expected = if (view != null) replacementFor(view, incoming) else null
            if (view != null && expected != null && incoming != null) {
                // The host usually re-applies a cell's background right before writing its icon, so
                // a plate dropped at claim time can come back. Dropping it again here — on the write
                // that would put the original artwork on screen — covers that ordering.
                ensurePlatesCleared(view)
                traceIntercept(view, incoming)
                val newArgs = chain.args.toTypedArray().also { it[0] = expected }
                chain.proceed(newArgs)
            } else {
                chain.proceed()
            }
        }
    }

    /**
     * Hooks `setImageBitmap(Bitmap)` on [owner]: if a claim exists, replaces the incoming bitmap
     * with the claimed drawable's bitmap. This covers the case where COUI overrides setImageBitmap
     * and never routes through setImageDrawable.
     */
    private fun hookBitmapMethod(hooks: HookRegistrar, owner: Class<*>, label: String) {
        val method = runCatching {
            owner.getDeclaredMethod("setImageBitmap", android.graphics.Bitmap::class.java)
        }.getOrNull() ?: return
        hooks.install(method, label) { chain ->
            val view = chain.instanceOrNull as? ImageView
            val incoming = chain.args.getOrNull(0) as? android.graphics.Bitmap
            if (view != null && incoming != null) {
                val entry = synchronized(watched) { watched[view] }
                val claimedBitmap = (entry?.expected as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (claimedBitmap != null && claimedBitmap !== incoming) {
                    ensurePlatesCleared(view)
                    traceIntercept(view, null)
                    val newArgs = chain.args.toTypedArray().also { it[0] = claimedBitmap }
                    chain.proceed(newArgs)
                } else {
                    chain.proceed()
                }
            } else {
                chain.proceed()
            }
        }
    }

    private fun traceIntercept(view: ImageView, incoming: Drawable?) {
        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.GameAssistantWatchdog,
            message = "图标看门狗：拦下宿主写入，换回主题图标",
            attributes = mapOf(
                "stage" to "intercept",
                "view" to view.javaClass.name,
                "incoming" to (incoming?.javaClass?.name ?: "bitmap"),
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
    fun watch(icon: ImageView, drawable: Drawable, windowMillis: Long, clearPlate: Boolean = false) {
        // Register first: the write below goes through the interception hook itself, and the fresh
        // entry must already be in place or a rebinding cell would briefly swap back to the old
        // claim. Bumping the generation also cancels any pending background resolution of an
        // earlier bind of this recycled view.
        //
        // Dock cells also lose their backing plates here — the icon's own and, when this build's
        // skin layer paints it on a wrapper, an icon-sized ancestor's. Themed artwork is drawn on
        // top of that plate rather than replacing it, so the plate shows through the artwork's
        // transparent corners as a lighter rim (reported 2026-11-21: "没有完全覆盖，旁边还是有阴影"、
        // "四个角的颜色和别处不一样"). Dropping it lets the panel itself show at the corners, which is
        // how re.14 looked. The message-box badge passes clearPlate = false: its backing is an
        // app-icon frame, not a cell plate.
        synchronized(watched) {
            generationOf(icon).incrementAndGet()
            val previous = watched[icon]
            val plates = previous?.plates ?: if (clearPlate) takePlates(icon) else emptyList()
            watched[icon] = Entry(drawable, plates)
        }
        icon.setImageDrawable(drawable)
        // Dock cells only: dump the real colours once per process so the next report about the pale
        // corners can be answered from the log instead of another guess.
        if (clearPlate) {
            GameSlotPixelProbe.probeOnce(icon, diagnostics)
            installChromeSweep(icon)
        } else {
            dropChromeSweep(icon)
        }
        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.GameAssistantWatchdog,
            message = "图标看门狗：绑定后接管图标视图",
            attributes = mapOf(
                "stage" to "watch",
                "view" to icon.javaClass.name,
                "drawable" to drawable.javaClass.name,
                "plates" to (synchronized(watched) { watched[icon]?.plates }
                    ?.joinToString(">") {
                        "${it.background?.javaClass?.simpleName ?: "none"}" +
                            "/${it.foreground?.javaClass?.simpleName ?: "none"}"
                    }
                    ?.ifEmpty { "none" } ?: "none"),
                // Only worth logging when nothing was found: it tells the next report whether the
                // pale corners come from an ancestor this module never touched.
                "chain" to (if (synchronized(watched) { watched[icon]?.plates }?.isEmpty() != false) {
                    describeChain(icon)
                } else {
                    "-"
                }),
            ),
            occurrence = OccurrencePolicy.RateLimited("games:watchdog_watch", 2_000L),
        )
    }

    /**
     * Takes the cell's backing off [icon] and off any icon-sized ancestor, returning what it took.
     *
     * The icon view is the first candidate. When the skin layer in this build paints the plate on a
     * wrapper instead, the wrapper is still exactly icon-sized — a row or the panel is not — so the
     * size comparison is what keeps this from stripping a whole row's background.
     */
    private fun takePlates(icon: ImageView): List<Plate> {
        val taken = ArrayList<Plate>(2)
        val background = icon.background
        val foreground = icon.foreground?.takeUnless { it is RippleDrawable }
        if (background != null || foreground != null) {
            taken += Plate(icon, background, foreground)
            icon.background = null
            if (foreground != null) icon.foreground = null
        }
        if (icon.width <= 0 || icon.height <= 0) return taken
        var parent = icon.parent
        var depth = 0
        while (parent is android.view.View && depth < PLATE_ANCESTOR_DEPTH) {
            val wrapper: android.view.View = parent
            val sized = wrapper.width in 1..(icon.width * PLATE_MAX_RATIO).toInt() &&
                wrapper.height in 1..(icon.height * PLATE_MAX_RATIO).toInt()
            if (sized) {
                wrapper.background?.let {
                    taken += Plate(wrapper, it)
                    wrapper.background = null
                }
            }
            parent = wrapper.parent
            depth++
        }
        return taken
    }

    /** Puts back every plate the claim took, if the host re-applied one in the meantime. */
    private fun ensurePlatesCleared(icon: ImageView) {
        val plates = synchronized(watched) { watched[icon]?.plates } ?: return
        for (plate in plates) {
            if (plate.background != null && plate.view.background != null) {
                runCatching { plate.view.background = null }
            }
            if (plate.foreground != null && plate.view.foreground != null) {
                runCatching { plate.view.foreground = null }
            }
        }
    }

    /** One live pre-draw sweep, with the observer it was registered on so it can be re-armed. */
    private class Sweep(val owner: ViewTreeObserver, val listener: ViewTreeObserver.OnPreDrawListener)

    private val sweeps = WeakHashMap<ImageView, Sweep>()

    /**
     * Keeps the dock's own chrome off a claimed icon for as long as the claim lives.
     *
     * Stripping it once at bind is not enough: the skin layer re-applies the icon's outline after
     * the cell is bound (which is why the claim-time log reports `plates=none` while the ring is
     * still on screen). A pre-draw pass is the cheapest place to win that race — it runs after the
     * host has had its say and before the frame is painted, and with a claim the check is two field
     * reads. A ripple foreground is left alone: it is press feedback, and its idle state paints
     * nothing.
     */
    private fun installChromeSweep(icon: ImageView) {
        val existing = synchronized(sweeps) { sweeps[icon] }
        val observer = runCatching { icon.viewTreeObserver }.getOrNull()
        if (existing != null && existing.owner === observer) return
        if (observer == null || !observer.isAlive) return
        // A recycled cell can come back attached to a different observer; the old registration is
        // dead weight then, so it is dropped before the new one goes in.
        if (existing != null) runCatching { existing.owner.removeOnPreDrawListener(existing.listener) }
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (!isClaimed(icon)) {
                dropChromeSweep(icon)
            } else {
                sweepChrome(icon)
            }
            true
        }
        synchronized(sweeps) { sweeps[icon] = Sweep(observer, listener) }
        runCatching { observer.addOnPreDrawListener(listener) }
    }

    private fun dropChromeSweep(icon: ImageView) {
        val sweep = synchronized(sweeps) { sweeps.remove(icon) } ?: return
        runCatching { sweep.owner.removeOnPreDrawListener(sweep.listener) }
    }

    private fun isClaimed(icon: ImageView): Boolean = synchronized(watched) { watched[icon] != null }

    /** Drops a plate or a skin outline re-applied to a claimed icon after bind. */
    private fun sweepChrome(icon: ImageView) {
        val background = icon.background
        if (background != null) {
            icon.background = null
            traceChrome(icon, "background", background)
        }
        val foreground = icon.foreground
        if (foreground != null && foreground !is RippleDrawable) {
            icon.foreground = null
            traceChrome(icon, "foreground", foreground)
        }
    }

    private fun traceChrome(view: ImageView, kind: String, drawable: Drawable) {
        diagnostics.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.GameAssistantWatchdog,
            message = "图标视图自带的底板/描边已摘除",
            attributes = mapOf(
                "stage" to "chrome",
                "kind" to kind,
                "view" to view.javaClass.simpleName,
                "drawable" to drawable.javaClass.name,
                "size" to "${view.width}x${view.height}",
            ),
            occurrence = OccurrencePolicy.Once("games:chrome:$kind:${drawable.javaClass.name}"),
        )
    }

    /** `Class#bg/fg@64x64<Class#none/none@64x64` for the claim's view and its ancestors. */
    private fun describeChain(icon: ImageView): String {
        val parts = ArrayList<String>(PLATE_ANCESTOR_DEPTH + 1)
        var next: View? = icon
        var depth = 0
        while (depth <= PLATE_ANCESTOR_DEPTH) {
            val current: View = next ?: break
            parts += "${current.javaClass.simpleName}#${current.background?.javaClass?.simpleName ?: "none"}" +
                "/${current.foreground?.javaClass?.simpleName ?: "none"}" +
                "@${current.width}x${current.height}"
            next = current.parent as? View
            depth++
        }
        return parts.joinToString("<")
    }

    /**
     * Drops any claim on [icon] so host writes land untouched, and bumps the generation so a
     * background resolution started by the previous bind can no longer claim the view. Called when
     * a recycled cell binds to a package whose themed artwork is not cached yet.
     */
    fun release(icon: ImageView) {
        val plates = synchronized(watched) {
            generationOf(icon).incrementAndGet()
            watched.remove(icon)?.plates
        }
        dropChromeSweep(icon)
        // A recycled view that binds to a package without themed artwork must look like an
        // untouched dock cell, so the plates go back with the claim.
        plates?.forEach { plate ->
            runCatching {
                if (plate.background != null) plate.view.background = plate.background
                if (plate.foreground != null) plate.view.foreground = plate.foreground
            }
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
