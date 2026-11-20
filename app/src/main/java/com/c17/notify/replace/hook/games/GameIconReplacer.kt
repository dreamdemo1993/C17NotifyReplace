package com.c17.notify.replace.hook.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
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

    /**
     * Dock variants of themed artwork, keyed by the exact bitmap the theme cache handed out.
     *
     * Keying on the instance instead of the package name is what keeps this from going stale: a
     * theme switch drops the provider's cache, a fresh bitmap is resolved, and the old entry simply
     * has no key left to match. A package-name key would keep painting the previous theme's icon
     * until the process happened to restart.
     */
    private val dockVariants = java.util.WeakHashMap<Bitmap, Bitmap>()

    /**
     * @param fill re-masks the artwork to fill the game assistant's rounded-square dock cell, see
     *   [fillRoundedSquare]. Only the app strip asks for it; desktop, barrage and message-box
     *   artwork must stay exactly as the theme engine drew it.
     */
    fun drawableFor(site: String, packageName: String?, fill: Boolean = false): Drawable? {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == SELF_PACKAGE) return null
        val context = application() ?: return null
        val resolved = bitmapFor(context, pkg) ?: run {
            traceMiss(site, pkg)
            return null
        }
        val bitmap = if (fill) filledBitmap(resolved) else resolved
        traceReplace(site, pkg, fill)
        return BitmapDrawable(context.resources, bitmap).mutate()
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
    fun cachedDrawableFor(packageName: String?, fill: Boolean = false): Drawable? {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == SELF_PACKAGE) return null
        val context = application() ?: return null
        val now = SystemClock.elapsedRealtime()
        if (missUntil.get(pkg)?.let { it > now } == true) return null
        val cached = themeIcons.peekCached(context, pkg) ?: return null
        val bitmap = if (fill) filledBitmap(cached) else cached
        return BitmapDrawable(context.resources, bitmap).mutate()
    }

    /**
     * The dock-shaped variant of [source], computed once per resolved bitmap.
     *
     * Re-masking costs a bitmap allocation and a draw; a flung app strip binds the same cells over
     * and over, so the answer is memoised. Bitmaps that cannot be re-drawn are memoised only when a
     * distinct copy was produced — caching an identity mapping would make the value keep its own
     * key alive inside the `WeakHashMap` and leak one bitmap per package.
     */
    private fun filledBitmap(source: Bitmap): Bitmap {
        synchronized(dockVariants) { dockVariants[source] }?.let { return it }
        val filled = runCatching { fillRoundedSquare(source) }.getOrDefault(source)
        if (filled !== source) {
            synchronized(dockVariants) { dockVariants[source] = filled }
        }
        return filled
    }

    /**
     * Re-masks themed artwork so it fills the dock cell edge to edge.
     *
     * 11.0.3 repainted the app strip as rounded-square cells and dropped the shape transform the old
     * cell used to apply, so the theme bitmap now reaches the screen exactly as the theme engine
     * drew it: a squircle whose four corners are transparent. Measured on a 1920 px screenshot the
     * cell is 77 px with a corner radius of roughly 33 % — a third of every side is empty, the
     * cell's own backing shows through and the icon reads as "smaller than its slot" (report
     * 2026-11-20 "主题图标覆盖不完全").
     *
     * The artwork is zoomed just far enough that the cell's four corners land on opaque pixels, then
     * clipped to a rounded square with [CORNER_RATIO] corners: the cell is fully covered while the
     * silhouette stays soft instead of turning into a hard square. Artwork that already reaches its
     * own edges is left unzoomed, so a theme shipping square icons is not cropped.
     */
    private fun fillRoundedSquare(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width < 2 || height < 2) return source
        val zoom = cornerZoom(source)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val radius = minOf(width, height) * CORNER_RATIO
        val outline = Path().apply {
            addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, Path.Direction.CCW)
        }
        canvas.save()
        canvas.clipPath(outline)
        val insetX = (width - width / zoom) / 2f
        val insetY = (height - height / zoom) / 2f
        // `Canvas` only pairs an integer source rect with a float destination rect; the one-pixel
        // truncation is invisible at icon scale and keeps the draw on a single API level.
        val visible = Rect(
            insetX.toInt(),
            insetY.toInt(),
            (width - insetX).toInt(),
            (height - insetY).toInt(),
        )
        canvas.drawBitmap(
            source,
            visible,
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true },
        )
        canvas.restore()
        return output
    }

    /**
     * Smallest zoom at which the output's four corners sit on opaque artwork.
     *
     * Walked in [ZOOM_STEPS] rather than solved, because the exact corner shape is the theme
     * engine's business (squircle, rounded square, nothing at all). Sampling four pixels per step
     * keeps this far below the cost of the cross-process resolve that produced the bitmap.
     */
    private fun cornerZoom(source: Bitmap): Float {
        val width = source.width
        val height = source.height
        val probeX = (width * CORNER_PROBE_RATIO).toInt().coerceAtLeast(1)
        val probeY = (height * CORNER_PROBE_RATIO).toInt().coerceAtLeast(1)
        for (zoom in ZOOM_STEPS) {
            val left = sourceIndex(probeX, width, zoom)
            val right = sourceIndex(width - 1 - probeX, width, zoom)
            val top = sourceIndex(probeY, height, zoom)
            val bottom = sourceIndex(height - 1 - probeY, height, zoom)
            // A bitmap that cannot be sampled (hardware or already recycled) must not be cropped on
            // a guess: treat it as covered and leave the artwork at its own size.
            val covered = runCatching {
                opaqueAt(source, left, top) && opaqueAt(source, right, top) &&
                    opaqueAt(source, left, bottom) && opaqueAt(source, right, bottom)
            }.getOrDefault(true)
            if (covered) return zoom
        }
        return ZOOM_STEPS.last()
    }

    /** Where [point] of the output lands in the source once it is scaled by [zoom] and centred. */
    private fun sourceIndex(point: Int, size: Int, zoom: Float): Int =
        ((point - size / 2f) / zoom + size / 2f).toInt().coerceIn(0, size - 1)

    private fun opaqueAt(source: Bitmap, x: Int, y: Int): Boolean =
        Color.alpha(source.getPixel(x, y)) >= OPAQUE_ALPHA

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

    private fun traceReplace(site: String, pkg: String, fill: Boolean) {
        diagnostics.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.GameAssistantIconReplaced,
            message = "游戏助手图标已替换为主题图标",
            attributes = mapOf(
                "site" to site,
                "pkg" to pkg,
                "fill" to if (fill) "dock" else "artwork",
            ),
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

        /** Corner radius of a dock icon, as a fraction of its side. Kept soft, not a hard square. */
        const val CORNER_RATIO = 0.20f

        /** How far inside the output corner a pixel still counts as "the corner itself". */
        const val CORNER_PROBE_RATIO = 0.03f

        /** Alpha from which a pixel counts as painted artwork rather than a transparent corner. */
        const val OPAQUE_ALPHA = 200

        /**
         * Zoom candidates, smallest first. 1.0 already covers artwork that reaches its own edges;
         * 1.56 covers a corner radius up to about 40 % of the side, past anything a theme ships.
         */
        val ZOOM_STEPS = floatArrayOf(1.0f, 1.08f, 1.16f, 1.24f, 1.32f, 1.40f, 1.48f, 1.56f)
    }
}
