package com.c17.notify.replace.hook.games

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.widget.ImageView
import com.c17.notify.replace.diagnostics.DiagnosticEvent
import com.c17.notify.replace.diagnostics.DiagnosticLevel
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.diagnostics.OccurrencePolicy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot pixel report for a dock cell, taken once per process.
 *
 * Four releases in a row guessed at why the themed artwork shows a paler rim: a host plate at claim
 * time (re.14.4), an icon slot larger than the artwork (re.14.3), a skin layer re-applying the
 * backing (re.14.5 — the dump proved the whole chain has no background at all). Every guess cost a
 * build plus an install. This reports the actual pixels instead.
 *
 * - `artwork`: the themed bitmap's own corner, edge and centre colours **with alpha**, plus the
 *   alpha bounding boxes (`opaque=` alpha ≥ 200, `faint=` alpha 1..199). A theme icon usually
 *   carries a soft outer glow: if the faint box is larger than the opaque one, the artwork's edge is
 *   semi-transparent, and `ThemeIconProvider` scales the *faint* box up to the full square — which
 *   leaves a translucent ring around the artwork that shows whatever is behind it. That would look
 *   exactly like the reported "一圈比图标淡的颜色，四个角的颜色和别处不一样".
 * - `cell`, `up2`, `up3`: the icon's ancestors rendered offscreen with the icon in place, so
 *   siblings, `onDraw` output and `foreground` are included — none of which `background` inspection
 *   can see.
 * - everything after `|`: four samples just outside the icon, telling apart "the pale area ends at
 *   the artwork's edge" from "it extends past it".
 */
internal object GameSlotPixelProbe {

    private val probed = AtomicBoolean(false)

    /** How far above the icon view the report walks: cell, then two wrappers up. */
    private const val MAX_ANCESTOR = 3

    /** Inset used for the nine samples inside the icon, in pixels. */
    private const val INSET = 2

    /** Distance of the four outside samples, in pixels. */
    private const val OUTSIDE = 6

    /** Alpha at or above this counts as artwork rather than glow. */
    private const val OPAQUE_ALPHA = 200

    /** Guards against capturing a full-window ancestor on an unexpected hierarchy. */
    private const val MAX_CAPTURE_PIXELS = 4_000_000L

    fun probeOnce(icon: ImageView, diagnostics: Diagnostics) {
        if (!probed.compareAndSet(false, true)) return
        icon.post { runCatching { report(icon, diagnostics) } }
    }

    private fun report(icon: ImageView, diagnostics: Diagnostics) {
        if (icon.width <= 0 || icon.height <= 0) {
            // Bound before layout: give the next bind a chance instead of burning the one shot.
            probed.set(false)
            return
        }
        val attributes = LinkedHashMap<String, Any?>()
        val drawable = icon.drawable
        attributes["view"] = "${icon.javaClass.simpleName}@${icon.width}x${icon.height}"
        attributes["scaleType"] = icon.scaleType?.name ?: "none"
        attributes["padding"] = "${icon.paddingLeft},${icon.paddingTop}," +
            "${icon.paddingRight},${icon.paddingBottom}"
        attributes["bg"] = icon.background?.javaClass?.simpleName ?: "none"
        attributes["fg"] = icon.foreground?.javaClass?.simpleName ?: "none"
        attributes["drawable"] = "${drawable?.javaClass?.simpleName ?: "none"}" +
            "@${drawable?.intrinsicWidth}x${drawable?.intrinsicHeight}" +
            " bounds=${drawable?.bounds?.toShortString() ?: "-"}"
        val artwork = (drawable as? BitmapDrawable)?.bitmap
        attributes["artwork"] = if (artwork == null) {
            "none"
        } else {
            "${artwork.width}x${artwork.height}@${artwork.density} " +
                alphaBoxes(artwork) + " " +
                squareSamples(artwork, 0, 0, artwork.width, artwork.height)
        }

        var anchor: View = icon
        var depth = 0
        while (depth < MAX_ANCESTOR) {
            val parent = anchor.parent as? View ?: break
            anchor = parent
            depth++
            val bitmap = capture(anchor) ?: continue
            val key = if (depth == 1) "cell" else "up$depth"
            attributes[key] = "${anchor.javaClass.simpleName}@${anchor.width}x${anchor.height} " +
                squareSamples(bitmap, leftIn(icon, anchor), topIn(icon, anchor),
                    icon.width, icon.height)
        }

        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.GameAssistantSlotProbe,
            message = "游戏助手侧栏图标像素取样",
            attributes = attributes,
            occurrence = OccurrencePolicy.Once("games:slot_probe"),
        )
    }

    /**
     * `opaque=l,t,r,b faint=l,t,r,b` for the bitmap's alpha, or `opaque=none` when nothing is drawn.
     * A faint box larger than the opaque one means the artwork's edge is semi-transparent.
     */
    private fun alphaBoxes(bitmap: Bitmap): String {
        var opaqueLeft = bitmap.width; var opaqueTop = bitmap.height
        var opaqueRight = -1; var opaqueBottom = -1
        var faintLeft = bitmap.width; var faintTop = bitmap.height
        var faintRight = -1; var faintBottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val alpha = bitmap.getPixel(x, y) ushr 24
                if (alpha <= 0) continue
                if (x < faintLeft) faintLeft = x
                if (x > faintRight) faintRight = x
                if (y < faintTop) faintTop = y
                if (y > faintBottom) faintBottom = y
                if (alpha < OPAQUE_ALPHA) continue
                if (x < opaqueLeft) opaqueLeft = x
                if (x > opaqueRight) opaqueRight = x
                if (y < opaqueTop) opaqueTop = y
                if (y > opaqueBottom) opaqueBottom = y
            }
        }
        val opaque = if (opaqueRight < 0) "none" else "$opaqueLeft,$opaqueTop,$opaqueRight,$opaqueBottom"
        val faint = if (faintRight < 0) "none" else "$faintLeft,$faintTop,$faintRight,$faintBottom"
        return "opaque=$opaque faint=$faint"
    }

    /** Renders [view] into an offscreen bitmap; null when it has no size or the draw fails. */
    private fun capture(view: View): Bitmap? {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return null
        if (width.toLong() * height > MAX_CAPTURE_PIXELS) return null
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        }.getOrNull()
    }

    private fun leftIn(inner: View, outer: View): Int {
        val a = IntArray(2).also { inner.getLocationInWindow(it) }
        val b = IntArray(2).also { outer.getLocationInWindow(it) }
        return a[0] - b[0]
    }

    private fun topIn(inner: View, outer: View): Int {
        val a = IntArray(2).also { inner.getLocationInWindow(it) }
        val b = IntArray(2).also { outer.getLocationInWindow(it) }
        return a[1] - b[1]
    }

    /**
     * Nine colours inside the rectangle plus four just outside it, as `#aarrggbb` joined by commas
     * and split into two groups by `|`: `TL,TR,BR,BL,T,R,B,L,C|outT,outR,outB,outL`.
     */
    private fun squareSamples(
        bitmap: Bitmap,
        offsetX: Int,
        offsetY: Int,
        width: Int,
        height: Int,
    ): String {
        fun at(x: Int, y: Int): String {
            val px = x.coerceIn(0, bitmap.width - 1)
            val py = y.coerceIn(0, bitmap.height - 1)
            val color = bitmap.getPixel(px, py).toLong() and 0xFFFFFFFFL
            return "#" + color.toString(16).padStart(8, '0')
        }

        val inset = if (width > 2 * INSET && height > 2 * INSET) INSET else 0
        val left = offsetX + inset
        val right = offsetX + width - 1 - inset
        val top = offsetY + inset
        val bottom = offsetY + height - 1 - inset
        val centerX = offsetX + width / 2
        val centerY = offsetY + height / 2
        val inside = listOf(
            at(left, top), at(right, top), at(right, bottom), at(left, bottom),
            at(centerX, top), at(right, centerY), at(centerX, bottom), at(left, centerY),
            at(centerX, centerY),
        )
        val outside = listOf(
            at(centerX, top - OUTSIDE), at(right + OUTSIDE, centerY),
            at(centerX, bottom + OUTSIDE), at(left - OUTSIDE, centerY),
        )
        return inside.joinToString(",") + "|" + outside.joinToString(",")
    }
}
