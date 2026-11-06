package com.c17.notify.replace.hook.icon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.lang.reflect.Method

/**
 * Rebuilds the same composition ColorOS draws for "显示个人头像": the contact photo clipped to a
 * round rect with the app icon as a corner badge.
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

    /** Corner radius of the photo, as a fraction of its side (ColorOS uses a squircle). */
    private const val CORNER_RATIO = 0.30f

    /** Side of the corner badge, as a fraction of the whole picture. */
    private const val BADGE_RATIO = 0.36f

    /** Padding around the badge, as a fraction of the whole picture. */
    private const val BADGE_INSET_RATIO = 0.03f

    /** Clamp for the requested content size; anything outside is a measurement glitch. */
    private const val MIN_SIZE_PX = 48
    private const val MAX_SIZE_PX = 512

    @Volatile
    private var helper: Method? = null

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
            resolve(context)?.invoke(null, context, avatar, badge, packageName, contentSizePx)
                ?.let { return it as? Drawable }
        } catch (throwable: Throwable) {
            // Fall through to the local painter: a build without the helper is exactly when the
            // row would otherwise be left on the host's "app icon + badge".
        }
        return paint(context, avatar, badge, contentSizePx)
    }

    /**
     * The same picture, drawn here.
     *
     * The host's composer is an obfuscation-sensitive entry point, and on the build behind the
     * 「微信通知成原生图标了」 report it refused to resolve — so a row that *did* carry a contact
     * photo fell through to the ladder's last pass and got the themed icon instead. This painter
     * has no reflection in it: round-rect clip for the photo, the themed app icon as a corner
     * badge. Placement trails the host's (slightly smaller badge, a couple of dp of padding) so the
     * two are not pixel-identical, which is the price of not depending on its internals.
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
        val radius = size * CORNER_RATIO
        val avatarBounds = Rect(avatar.bounds)
        canvas.save()
        canvas.clipPath(
            Path().apply {
                addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, Path.Direction.CW)
            },
        )
        avatar.setBounds(0, 0, size, size)
        avatar.draw(canvas)
        canvas.restore()
        avatar.bounds = avatarBounds
        val badgeSize = (size * BADGE_RATIO).toInt()
        val inset = (size * BADGE_INSET_RATIO).toInt()
        val badgeBounds = Rect(badge.bounds)
        badge.setBounds(size - badgeSize - inset, size - badgeSize - inset, size - inset, size - inset)
        badge.draw(canvas)
        badge.bounds = badgeBounds
        BitmapDrawable(context.resources, bitmap)
    } catch (throwable: Throwable) {
        null
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
