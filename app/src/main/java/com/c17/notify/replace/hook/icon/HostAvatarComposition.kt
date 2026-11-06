package com.c17.notify.replace.hook.icon

import android.content.Context
import android.graphics.drawable.Drawable
import java.lang.reflect.Method

/**
 * Access to the composition ColorOS draws for "显示个人头像": the contact photo with the app icon as
 * a corner badge, built by `LayerDrawableWithAppIconKt.createBackgroundWithAppIcon`.
 *
 * The module deliberately does **not** redraw this picture. Two attempts at painting it locally —
 * a round-rect clip, then a sampled squircle — both looked wrong next to the stock result: the
 * mask is baked by the host together with the badge, so any copy of it changes the avatar outline
 * *and* shaves the badge (reports: 「头像形状切割的很奇怪」, then 「连带角标都被裁剪了 恢复原生通知
 * 的形状」). The only supported way to influence this picture is to swap the **badge** argument in
 * the host's own call, which leaves every pixel of geometry to the host.
 */
internal object HostAvatarComposition {
    private const val HELPER_CLASS =
        "com.oplus.systemui.notification.row.wrapper.LayerDrawableWithAppIconKt"
    private const val HELPER_METHOD = "createBackgroundWithAppIcon"

    @Volatile
    private var helper: Method? = null

    /**
     * The host's own composer.
     *
     * `createBackgroundWithAppIcon(Context, origin: Drawable, badge: Drawable, pkg: String,
     * contentSizePx: Integer): Drawable`. Null when this ColorOS build does not expose it, in which
     * case callers leave the host completely alone.
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
