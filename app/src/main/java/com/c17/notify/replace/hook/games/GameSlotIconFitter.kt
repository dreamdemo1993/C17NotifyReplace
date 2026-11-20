package com.c17.notify.replace.hook.games

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.c17.notify.replace.diagnostics.DiagnosticEvent
import com.c17.notify.replace.diagnostics.DiagnosticLevel
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.diagnostics.OccurrencePolicy
import kotlin.math.abs
import kotlin.math.max

/**
 * Draws themed artwork at the size of the cell it is about to be painted into.
 *
 * The game assistant decodes an app icon through `oke.J` → `oke.I(context, shape, w, h)`, which
 * calls `RequestOptions.override(w, w)`: the host's own icon reaches the cell already sized to the
 * icon slot. Themed artwork comes from a different pipeline and is rendered at
 * `android.R.dimen.app_icon_size` ([com.c17.notify.replace.hook.icon.ThemeIconProvider]), so when
 * the dock's icon size and `app_icon_size` disagree, the themed icon is drawn one notch smaller
 * than its slot and the cell's own backing shows as a ring around it (report 2026-11-21
 * "图标比图标位小一圈").
 *
 * Only the scale is corrected here — the artwork keeps its own silhouette, its own corner radius
 * and its own proportions. Zooming the content or painting the corners in was tried in re.14.1 and
 * read as "用主题色填满", so this deliberately does neither: the icon is simply drawn as big as the
 * host would have drawn its own icon in the same cell.
 *
 * The view is the slot: whatever `ImageView` the host hands us has already been measured to the
 * cell's icon size, so its bounds are the measurement. A cell that is still unmeasured (first bind
 * of a fresh view) is skipped rather than guessed at, and
 * [com.c17.notify.replace.hook.games.GameIconWatchdog] retries once the view has been laid out.
 */
internal object GameSlotIconFitter {

    /**
     * Fitted artwork keyed by the exact bitmap the theme cache handed out, then by the slot size.
     *
     * Keying on the bitmap instance is what keeps this from going stale across a theme switch: the
     * provider drops its cache, a fresh bitmap is resolved, and the old entry has no key left to
     * match. A package-name key would keep painting the previous theme's artwork until the process
     * happened to restart. The value never references the key, so the source can still be collected
     * and the entry disappears with it.
     */
    private val variants = java.util.WeakHashMap<Bitmap, MutableMap<Int, Bitmap>>()

    /**
     * [drawable] redrawn to the size of [icon]'s icon slot, or [drawable] itself when there is
     * nothing to correct: the view is not measured yet, the artwork already carries the right size,
     * or the two differ by so much that a resize is more likely a layout we should not touch.
     */
    fun fit(icon: ImageView, drawable: Drawable, diagnostics: Diagnostics?): Drawable {
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return drawable
        // The content box, not the view box: an `ImageView` lays its drawable out inside its
        // padding, so padding would otherwise be counted as part of the slot and the artwork would
        // be drawn over it.
        val slot = minOf(
            icon.width - icon.paddingLeft - icon.paddingRight,
            icon.height - icon.paddingTop - icon.paddingBottom,
        )
        val artwork = minOf(bitmap.width, bitmap.height)
        if (slot <= 0 || artwork <= 0) return drawable
        val scale = slot.toFloat() / artwork.toFloat()
        if (abs(scale - 1f) <= SCALE_SLACK) return drawable
        val applied = scale in MIN_SCALE..MAX_SCALE
        // One line per burst is enough: it answers "did the module see the cell at all" and "how
        // far off was the artwork", which is the whole question when a cell still looks wrong.
        diagnostics?.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.GameAssistantSlotFit,
            message = if (applied) "侧栏图标按图标位尺寸重绘" else "侧栏图标与图标位尺寸相差过大，保持原样",
            attributes = mapOf(
                "slot" to slot.toString(),
                "artwork" to artwork.toString(),
                "scale" to "%.2f".format(scale),
                "view" to "${icon.width}x${icon.height}",
                "scaleType" to (icon.scaleType?.name ?: "unknown"),
                "applied" to applied.toString(),
            ),
            occurrence = OccurrencePolicy.RateLimited("games:slot_fit", 5000L),
        )
        if (!applied) return drawable
        val fitted = cached(bitmap, slot) ?: return drawable
        return BitmapDrawable(icon.context.resources, fitted).mutate()
    }

    private fun cached(source: Bitmap, slot: Int): Bitmap? {
        synchronized(variants) { variants[source]?.get(slot) }?.let { return it }
        val produced = runCatching { render(source, slot) }.getOrNull() ?: return null
        synchronized(variants) { variants.getOrPut(source) { HashMap(2) }[slot] = produced }
        return produced
    }

    /**
     * Draws [source] into a [slot] square, scaled up or down just enough to cover it while keeping
     * the artwork's own proportions. Themed icons are square, so "cover" and "fit" are the same
     * thing in practice; using the larger of the two ratios only matters for artwork that is not
     * square, where letterboxing would leave exactly the gap this is meant to remove.
     */
    private fun render(source: Bitmap, slot: Int): Bitmap {
        val output = Bitmap.createBitmap(slot, slot, Bitmap.Config.ARGB_8888)
        val ratio = max(slot.toFloat() / source.width, slot.toFloat() / source.height)
        val width = source.width * ratio
        val height = source.height * ratio
        val left = (slot - width) / 2f
        val top = (slot - height) / 2f
        Canvas(output).drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            RectF(left, top, left + width, top + height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return output
    }

    /** Below this the two sizes are the same for any purpose a screen can show. */
    private const val SCALE_SLACK = 0.02f

    /**
     * Resizing bounds. A themed icon is expected to be within a few percent of the dock's icon
     * size; anything past these is a layout this module has no business rescaling blind, so the
     * artwork is left exactly as the theme engine drew it and the numbers go to the log instead.
     */
    private const val MIN_SCALE = 0.5f
    private const val MAX_SCALE = 1.75f
}
