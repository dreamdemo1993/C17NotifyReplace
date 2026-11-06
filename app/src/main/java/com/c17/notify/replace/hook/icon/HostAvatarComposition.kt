package com.c17.notify.replace.hook.icon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.lang.reflect.Method

/**
 * Rebuilds the same composition ColorOS draws for "显示个人头像": the contact photo masked by the
 * system squircle with the app icon as a corner badge.
 *
 * The host builds it inside a coroutine on `Dispatchers.Default`, so the icon view keeps showing the
 * plain `smallIcon` for a few frames and then jumps to the avatar. Letting the module pre-fill that
 * window with the host's own helper keeps the two frames pixel-identical — the helper is the very
 * function the coroutine calls, and whatever it renders later simply replaces this drawable.
 */
internal object HostAvatarComposition {
    private const val HELPER_CLASS =
        "com.oplus.systemui.notification.row.wrapper.LayerDrawableWithAppIconKt"
    private const val HELPER_METHOD = "createBackgroundWithAppIcon"

    /**
     * Exponent of the superellipse that masks every row icon on ColorOS.
     *
     * The mask is **not** a rounded rectangle: a round rect keeps a constant 90° corner arc and then
     * meets the straight edge with a curvature jump, while the system draws a squircle whose
     * curvature changes continuously. Next to the themed app icons — which use the system mask — a
     * round-rect avatar reads as "切割得很奇怪": its corners look sharper and it has none of the
     * flat-ish edge the neighbours have. `|x|^n + |y|^n = 1` with n ≈ 3 matches the silhouette the
     * header icon view itself clips to, so both paths agree no matter which one ends up drawing.
     */
    private const val SQUIRCLE_EXPONENT = 3.0

    /** `2 / n` — the power each unit-circle coordinate is raised to when sampling the squircle. */
    private const val SQUIRCLE_POWER = 2.0 / SQUIRCLE_EXPONENT

    /** Segments used to sample the squircle; 240 keeps the curve smooth down to 48 px. */
    private const val SQUIRCLE_STEPS = 240

    /**
     * Side of the corner badge, as a fraction of the whole picture.
     *
     * The badge is clipped by the same squircle as the photo. Without that its square outer corner
     * punches straight through the avatar's rounded corner and the outline loses the system shape —
     * the second half of the "头像形状切割得很奇怪" report.
     */
    private const val BADGE_RATIO = 0.38f

    /** Padding around the badge, as a fraction of the whole picture. */
    private const val BADGE_INSET_RATIO = 0.03f

    /** Clamp for the requested content size; anything outside is a measurement glitch. */
    private const val MIN_SIZE_PX = 48
    private const val MAX_SIZE_PX = 512

    @Volatile
    private var helper: Method? = null

    /**
     * Which path produced the most recent composition — `host` (the system's own helper) or
     * `paint` (the local squircle). Diagnostics only; it is the one field that tells a future log
     * reader whether a wrong-looking avatar came from our painter or from ColorOS itself.
     */
    @Volatile
    var lastSource: String = "none"
        private set

    /**
     * `createBackgroundWithAppIcon(Context, origin: Drawable, badge: Drawable, pkg: String,
     * contentSizePx: Integer): Drawable`. Returns null whenever the helper is missing (other
     * ColorOS builds) or the composition throws, in which case callers keep the host result.
     */
    fun compose(
        context: Context,
        avatar: Drawable,
        badge: Drawable,
        packageName: String,
        contentSizePx: Int,
    ): Drawable? {
        try {
            val drawn = resolve(context)
                ?.invoke(null, context, avatar, badge, packageName, contentSizePx) as? Drawable
            if (drawn != null) {
                lastSource = "host"
                return drawn
            }
        } catch (throwable: Throwable) {
            // Fall through to the local painter: a build without the helper is exactly when the
            // row would otherwise be left on the host's "app icon + badge".
        }
        return paint(context, avatar, badge, contentSizePx)?.also { lastSource = "paint" }
    }

    /**
     * The same picture, drawn here.
     *
     * The host's composer is an obfuscation-sensitive entry point, and on the build behind the
     * 「微信通知成原生图标了」 report it refused to resolve — so a row that *did* carry a contact
     * photo fell through to the ladder's last pass and got the themed icon instead. This painter
     * has no reflection in it: the system squircle masks the photo, the themed app icon becomes a
     * corner badge inside the same mask. Placement trails the host's by a hair, which is the price
     * of not depending on its internals.
     */
    private fun paint(
        context: Context,
        avatar: Drawable,
        badge: Drawable,
        contentSizePx: Int,
    ): Drawable? = try {
        val size = contentSizePx.coerceIn(MIN_SIZE_PX, MAX_SIZE_PX)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val avatarBounds = Rect(avatar.bounds)
        val badgeBounds = Rect(badge.bounds)
        canvas.save()
        canvas.clipPath(squirclePath(size.toFloat()))
        fillCentred(avatar, size)
        avatar.draw(canvas)
        val badgeSize = (size * BADGE_RATIO).toInt()
        val inset = (size * BADGE_INSET_RATIO).toInt()
        badge.setBounds(size - badgeSize - inset, size - badgeSize - inset, size - inset, size - inset)
        badge.draw(canvas)
        canvas.restore()
        avatar.bounds = avatarBounds
        badge.bounds = badgeBounds
        BitmapDrawable(context.resources, bitmap)
    } catch (throwable: Throwable) {
        null
    }

    /**
     * Stretches [drawable] to *cover* a `size` square without distorting it.
     *
     * ColourOS draws the contact photo full-bleed, so the picture has to reach every edge; a plain
     * `setBounds(0, 0, size, size)` also forces a portrait shot into a square and squashes faces.
     * Scaling by the larger ratio and centring keeps the subject where the app cropped it.
     */
    private fun fillCentred(drawable: Drawable, size: Int) {
        val width = drawable.intrinsicWidth
        val height = drawable.intrinsicHeight
        if (width <= 0 || height <= 0) {
            drawable.setBounds(0, 0, size, size)
            return
        }
        val scale = maxOf(size.toFloat() / width, size.toFloat() / height)
        val scaledWidth = (width * scale).toInt()
        val scaledHeight = (height * scale).toInt()
        val left = (size - scaledWidth) / 2
        val top = (size - scaledHeight) / 2
        drawable.setBounds(left, top, left + scaledWidth, top + scaledHeight)
    }

    /**
     * `|x/a|^n + |y/a|^n = 1`, sampled — the silhouette ColorOS uses for every icon on a row.
     *
     * Sampled rather than approximated with Béziers: the shape is built once per composition and
     * 240 points are indistinguishable from an analytic curve at any size this module paints.
     */
    private fun squirclePath(size: Float): Path {
        val half = size / 2f
        val path = Path()
        for (index in 0 until SQUIRCLE_STEPS) {
            val angle = 2.0 * Math.PI * index / SQUIRCLE_STEPS
            val cosine = Math.cos(angle)
            val sine = Math.sin(angle)
            val x = half + half * Math.signum(cosine) * Math.pow(Math.abs(cosine), SQUIRCLE_POWER)
            val y = half + half * Math.signum(sine) * Math.pow(Math.abs(sine), SQUIRCLE_POWER)
            if (index == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
        }
        path.close()
        return path
    }

    /**
     * The host's own composer, for hooking. Resolved through the same cache as [compose] so both
     * paths always agree on the signature.
     */
    fun method(context: Context): Method? = resolve(context)

    private fun resolve(context: Context): Method? {
        helper?.let { return it }
        return try {
            Class.forName(HELPER_CLASS, false, context.classLoader)
                .getDeclaredMethod(
                    HELPER_METHOD,
                    Context::class.java,
                    Drawable::class.java,
                    Drawable::class.java,
                    String::class.java,
                    Integer::class.java,
                )
                .apply { isAccessible = true }
                .also { helper = it }
        } catch (throwable: Throwable) {
            null
        }
    }
}
