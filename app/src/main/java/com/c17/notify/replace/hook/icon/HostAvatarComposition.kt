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

    /**
     * Draws the composition with the host's own helper — mask, corner badge and all.
     *
     * Needed when ColorOS decides *not* to compose a picture for a row that does carry a sender
     * photo: it only builds "avatar + badge" for senders it recognises as people, so a personal
     * WeChat message can sit on the bare green glyph indefinitely (report: 「这是个人微信啊 我肯定
     * 要头像啊」). Calling the host's function keeps every pixel of geometry with the host; the
     * module only chooses the **arguments** — the sender photo and the themed app icon for the
     * badge.
     *
     * Returns an [Attempt] that is either the picture or the reason there is none. There is
     * deliberately no local fallback: both earlier attempts at painting this picture ourselves
     * produced the wrong silhouette (「头像形状切割的很奇怪」, then 「连带角标都被裁剪了 恢复原生
     * 通知的形状」), and a caller that gets no picture keeps whatever the host put in the slot
     * instead of inventing a shape.
     */
    fun compose(
        context: Context,
        avatar: Drawable,
        badge: Drawable,
        packageName: String,
        contentSizePx: Int,
    ): Attempt {
        val method = resolve(context)
            ?: return Attempt(null, "找不到 $HELPER_CLASS#$HELPER_METHOD")
        return try {
            val result = method.invoke(null, context, avatar, badge, packageName, contentSizePx)
            if (result is Drawable) {
                Attempt(result, null)
            } else {
                Attempt(null, "合成函数返回 ${result?.javaClass?.name ?: "null"}")
            }
        } catch (throwable: Throwable) {
            val cause = (throwable as? java.lang.reflect.InvocationTargetException)?.targetException
                ?: throwable
            Attempt(null, "合成函数抛异常 ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    /**
     * What the host answered: a picture, or the reason it could not produce one.
     *
     * The caller can only act on "no picture", but the log has to tell a missing helper (renamed by
     * ColorOS, or absent on this build) apart from a helper that exists and throws — those need
     * different fixes.
     */
    internal class Attempt(val drawable: Drawable?, val failure: String?)

    private fun resolve(context: Context): Method? {
        helper?.let { return it }
        val helperClass = try {
            Class.forName(HELPER_CLASS, false, context.classLoader)
        } catch (throwable: Throwable) {
            return null
        }
        // `contentSizePx` is an `Integer` in the Kotlin signature but the JVM sees whichever the
        // host compiler chose, so both the boxed and the primitive form have to be tried. When
        // neither matches (a renamed or reshaped build), fall back to the argument *shape*: the
        // function is the only one on that class taking (Context, Drawable, Drawable, String, int).
        val candidates = listOfNotNull(
            Integer::class.java,
            Int::class.javaPrimitiveType,
        )
        val resolved = candidates.firstNotNullOfOrNull { sizeType ->
            runCatching {
                helperClass.getDeclaredMethod(
                    HELPER_METHOD,
                    Context::class.java,
                    Drawable::class.java,
                    Drawable::class.java,
                    String::class.java,
                    sizeType,
                )
            }.getOrNull()
        } ?: helperClass.declaredMethods.firstOrNull { method ->
            val types = method.parameterTypes
            types.size == 5 &&
                types[0].isAssignableFrom(context.javaClass) &&
                types[1].isAssignableFrom(Drawable::class.java) &&
                types[2].isAssignableFrom(Drawable::class.java) &&
                types[3].isAssignableFrom(String::class.java) &&
                (types[4] == Int::class.javaPrimitiveType ||
                    types[4] == Integer::class.java ||
                    types[4] == Number::class.java)
        }
        return resolved?.apply { isAccessible = true }?.also { helper = it }
    }
}
