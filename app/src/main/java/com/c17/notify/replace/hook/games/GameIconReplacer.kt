package com.c17.notify.replace.hook.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import com.c17.notify.replace.diagnostics.DiagnosticEvent
import com.c17.notify.replace.diagnostics.DiagnosticLevel
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.diagnostics.OccurrencePolicy
import com.c17.notify.replace.hook.icon.ThemeIconProvider
import kotlin.math.abs

/**
 * Turns "this package's launcher icon" into "this package's theme icon" inside the game
 * assistant's own process.
 *
 * The game assistant is a normal app: it never goes through SystemUI's notification icon code, so
 * the theme icon has to be resolved again here. [ThemeIconProvider] is reused unchanged — it only
 * needs a [Context] and a package name, and it already caches per theme generation.
 */
internal class GameIconReplacer(
    private val diagnostics: Diagnostics,
    private val themeIcons: ThemeIconProvider,
    private val application: () -> Context?,
) {

    /** Packages the theme engine had no artwork for, with the time the answer may be re-asked. */
    private val missUntil = android.util.LruCache<String, Long>(MAX_CACHED_MISSES)

    /** Circular copies, keyed by the provider's cached bitmap — a theme switch yields new bitmaps. */
    private val circular = object : android.util.LruCache<Bitmap, Bitmap>(MAX_CACHED_MASKS) {}

    /**
     * @param circle crop the artwork to the [ApkIconShape.CIRCLE] the dock asks the host for. The
     *   dock's own icons are masked inside Glide (`oke.I` → `fj2`, which draws a filled circle);
     *   this module writes the drawable straight through `setImageDrawable` and therefore bypasses
     *   that pipeline, so the mask has to be reproduced here or the icon shows its unmasked
     *   rounded-square artwork with the theme's soft outer glow around it.
     */
    fun drawableFor(site: String, packageName: String?, circle: Boolean = false): Drawable? {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == SELF_PACKAGE) return null
        val context = application() ?: return null
        val bitmap = bitmapFor(context, pkg) ?: run {
            traceMiss(site, pkg)
            return null
        }
        traceReplace(site, pkg)
        val artwork = if (circle) circularArtwork(bitmap) else bitmap
        return BitmapDrawable(context.resources, artwork).mutate()
    }

    /**
     * Whether the theme engine has artwork for this package. Used to pick the real package name out
     * of a handful of strings the dock's model carries — the one that resolves is the one.
     */
    fun hasThemeIcon(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == SELF_PACKAGE) return false
        val context = application() ?: return false
        return bitmapFor(context, pkg) != null
    }

    /**
     * Cache-only variant of [drawableFor] for the scroll's hot path: answers only from what earlier
     * resolves stored and is guaranteed to never issue a cross-process call. A null means "resolve
     * it properly, off the UI thread" — nothing more.
     */
    fun cachedDrawableFor(packageName: String?, circle: Boolean = false): Drawable? {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == SELF_PACKAGE) return null
        val context = application() ?: return null
        val now = SystemClock.elapsedRealtime()
        if (missUntil.get(pkg)?.let { it > now } == true) return null
        val bitmap = themeIcons.peekCached(context, pkg) ?: return null
        val artwork = if (circle) circularArtwork(bitmap) else bitmap
        return BitmapDrawable(context.resources, artwork).mutate()
    }

    /**
     * Center-crops the artwork and fills a circle, i.e. the mask the dock's own Glide pipeline
     * applies for [ApkIconShape.CIRCLE].
     *
     * The dock asks the host for circle-shaped app icons (`oke.E` / `oke.F` are called with
     * `ApkIconShape.CIRCLE`), and the theme artwork is a rounded square wrapped in a translucent
     * glow. Writing it unmasked — which is what a `setImageDrawable` hook does — puts that glow on
     * screen as a paler rim around a square. Masking to a circle reproduces what the panel looked
     * like before the dock moved the mask into Glide, and the glow falls outside the mask entirely.
     *
     * Two details keep the outline clean (2026-11-21: "这个圆形有点奇怪 上下左右都有一点点遮挡
     * 不是正圆"):
     *
     * 1. The mask is sized from the artwork's **opaque** bounds, not from the bitmap's frame. The
     *    theme renders into a fixed square with glow and padding around the glyph, so a circle
     *    inscribed in the frame can be larger or smaller than the artwork itself and ends up
     *    biting into its edge.
     * 2. It is drawn at [CIRCLE_FILL] of that box, never touching it. The cell view
     *    (`COUIRoundImageView`, and `CustomGameAppCellView` above it) applies its own round clip;
     *    two masks of the same size shave a pixel off every side and the result no longer reads as
     *    a circle. Pulling the mask in leaves that clip with nothing to cut.
     */
    private fun circularArtwork(source: Bitmap): Bitmap {
        circular.get(source)?.let { return it }
        val size = minOf(source.width, source.height)
        if (size <= 0) return source
        val core = opaqueBounds(source)
        val result = runCatching {
            val shader = android.graphics.BitmapShader(
                source,
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP,
            )
            val matrix = android.graphics.Matrix()
            // 1:1, centred on the artwork rather than on the frame, so the circle wraps the glyph
            // even when the theme left uneven padding inside the bitmap.
            matrix.postTranslate(
                (size - source.width) / 2f,
                (size - source.height) / 2f,
            )
            if (core != null) {
                matrix.postTranslate(
                    size / 2f - core.exactCenterX() - (size - source.width) / 2f,
                    size / 2f - core.exactCenterY() - (size - source.height) / 2f,
                )
            }
            shader.setLocalMatrix(matrix)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            paint.shader = shader
            // A tiny opaque box means the measurement caught something odd rather than a glyph —
            // half the frame is the floor, so a bad reading can never collapse the icon to a dot.
            val box = (minOf(core?.width() ?: size, core?.height() ?: size)).toFloat()
                .coerceAtLeast(size * MIN_CIRCLE_BOX)
            val diameter = box * CIRCLE_FILL
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { target ->
                val center = size / 2f
                Canvas(target).drawCircle(center, center, diameter / 2f, paint)
            }
        }.getOrNull() ?: return source
        circular.put(source, result)
        return result
    }

    /**
     * Bounds of the artwork's opaque core, or null when every pixel is translucent.
     *
     * The theme engine draws the glyph, then a soft glow at a much lower alpha around it. Ignoring
     * anything below [CORE_ALPHA] keeps the glow out of the measurement, so the circle is fitted to
     * the glyph and not to the halo.
     */
    private fun opaqueBounds(source: Bitmap): Rect? {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        runCatching { source.getPixels(pixels, 0, width, 0, 0, width, height) }.getOrNull()
            ?: return null
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if ((pixels[row + x] ushr 24) < CORE_ALPHA) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return null
        return Rect(left, top, right + 1, bottom + 1)
    }

    /**
     * [ThemeIconProvider] caches hits for a long time but misses for exactly one second — the shade
     * needs that so a cold theme engine is retried quickly. A scrolling app strip calls this several
     * times per bound cell, and every cache miss there is a real cross-process call into the theme
     * service on the UI thread, which is what made scrolling the app-icon strip stutter. A miss is
     * therefore remembered locally for [MISS_TTL_MILLIS]: long enough to stop hammering the service
     * while a list is being flung, short enough that a theme switch or a slow engine recovers.
     */
    private fun bitmapFor(context: Context, pkg: String): Bitmap? {
        val now = SystemClock.elapsedRealtime()
        val cachedMissUntil = missUntil.get(pkg)
        if (cachedMissUntil != null && cachedMissUntil > now) return null
        val bitmap = themeIcons.resolveByPackage(context, pkg)
        if (bitmap == null) {
            missUntil.put(pkg, now + MISS_TTL_MILLIS)
        } else {
            missUntil.remove(pkg)
        }
        return bitmap
    }

    /**
     * One-shot check that the theme API really returns a *different* picture in this process.
     *
     * `UxIconPackageManagerExt` is a framework extension intended for system code; in an ordinary
     * app it can answer with the plain launcher icon instead of failing, and every replace below
     * then "succeeds" while the screen keeps showing the original artwork. Comparing the resolved
     * bitmap against the launcher icon tells the two cases apart from the log alone.
     */
    fun selfCheck(packageName: String) {
        val context = application() ?: return
        val themed = themeIcons.resolveByPackage(context, packageName) ?: return
        val plain = runCatching { context.packageManager.getApplicationIcon(packageName) }
            .getOrNull() ?: return
        val difference = sampleDifference(themed, plain) ?: return
        val isThemed = difference >= PLAIN_ARTWORK_TOLERANCE
        diagnostics.report(
            level = if (isThemed) DiagnosticLevel.Info else DiagnosticLevel.Warning,
            event = DiagnosticEvent.GameAssistantIconSelfCheck,
            message = if (isThemed) {
                "游戏助手进程的主题图标接口可用"
            } else {
                "游戏助手进程的主题图标接口返回了原始图标"
            },
            attributes = mapOf(
                "pkg" to packageName,
                "difference" to "%.1f".format(difference),
                "verdict" to if (isThemed) "themed" else "plain",
            ),
            occurrence = OccurrencePolicy.Once("games:self_check"),
        )
    }

    /** Mean per-channel difference over the centre, on a 16x16 render of both pictures. */
    private fun sampleDifference(themed: Bitmap, plain: Drawable): Double? {
        val left = sample(themed) ?: return null
        val right = sample(plain) ?: return null
        var total = 0L
        var count = 0
        for (y in MARGIN until SIZE - MARGIN) {
            for (x in MARGIN until SIZE - MARGIN) {
                val index = y * SIZE + x
                val first = left[index]
                val second = right[index]
                if (Color.alpha(first) < 32 || Color.alpha(second) < 32) continue
                total += abs(Color.red(first) - Color.red(second)).toLong()
                total += abs(Color.green(first) - Color.green(second)).toLong()
                total += abs(Color.blue(first) - Color.blue(second)).toLong()
                count++
            }
        }
        if (count == 0) return null
        return total.toDouble() / (count * 3.0)
    }

    private fun sample(source: Bitmap): IntArray? = try {
        val scaled = Bitmap.createScaledBitmap(source, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== source) scaled.recycle()
        pixels
    } catch (_: Throwable) {
        null
    }

    private fun sample(source: Drawable): IntArray? = try {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val previousBounds = Rect(source.bounds)
        source.setBounds(0, 0, SIZE, SIZE)
        source.draw(Canvas(bitmap))
        source.bounds = previousBounds
        val pixels = IntArray(SIZE * SIZE)
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        bitmap.recycle()
        pixels
    } catch (_: Throwable) {
        null
    }

    private fun traceReplace(site: String, pkg: String) {
        diagnostics.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.GameAssistantIconReplaced,
            message = "游戏助手图标已替换为主题图标",
            attributes = mapOf("site" to site, "pkg" to pkg),
            occurrence = OccurrencePolicy.RateLimited("games:replace:$site", TRACE_INTERVAL_MILLIS),
        )
    }

    /**
     * A miss is normal (a package without a themed icon), but it is also what a blocked theme API
     * looks like, so the first miss per site is kept in the release log.
     */
    private fun traceMiss(site: String, pkg: String) {
        diagnostics.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.GameAssistantIconSkipped,
            message = "游戏助手图标未解析到主题图标，保留原图标",
            attributes = mapOf("site" to site, "pkg" to pkg),
            occurrence = OccurrencePolicy.Once("games:skip:$site"),
        )
    }

    private companion object {
        const val SELF_PACKAGE = "com.oplus.games"
        const val TRACE_INTERVAL_MILLIS = 2_000L

        /** Long enough to survive a flung list, short enough for a theme switch to be picked up. */
        const val MISS_TTL_MILLIS = 10_000L
        const val MAX_CACHED_MISSES = 64

        const val SIZE = 16
        const val MARGIN = 3

        /** Below this the resolved bitmap *is* the launcher icon, i.e. nothing was themed. */
        const val PLAIN_ARTWORK_TOLERANCE = 8.0

        const val MAX_CACHED_MASKS = 64

        /**
         * Circle diameter as a fraction of the artwork's opaque box. Below 1 on purpose: a mask that
         * touches the artwork's edge gets clipped a pixel or two further in by the cell view's own
         * round mask (`COUIRoundImageView`), and the two combined stop reading as a circle. At a
         * 64 px cell this leaves roughly 1.5 px of clearance per side — invisible, but enough that
         * nothing else can shave the outline.
         */
        const val CIRCLE_FILL = 0.95f

        /** Alpha that counts as artwork rather than as the theme's soft outer glow. */
        const val CORE_ALPHA = 128

        /** Smallest opaque box, as a fraction of the frame, that the circle will be fitted to. */
        const val MIN_CIRCLE_BOX = 0.5f
    }
}
