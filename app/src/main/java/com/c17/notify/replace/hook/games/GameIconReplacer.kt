package com.c17.notify.replace.hook.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

    fun drawableFor(site: String, packageName: String?): Drawable? {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == SELF_PACKAGE) return null
        val context = application() ?: return null
        val bitmap = bitmapFor(context, pkg) ?: run {
            traceMiss(site, pkg)
            return null
        }
        traceReplace(site, pkg)
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
    }
}
