package com.c17.notify.replace.hook.systemserver

import android.app.Notification
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.hook.HookContract
import com.c17.notify.replace.hook.HookRegistrar
import com.c17.notify.replace.hook.memberMissing
import com.c17.notify.replace.hook.reflect.Reflection
import com.c17.notify.replace.hook.runtimeFailure

internal class SystemServerHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
) {
    fun install(classLoader: ClassLoader) {
        val helperClass = Reflection.loadClassOrNull(HELPER_CLASS, classLoader) { cause ->
            diagnostics.memberMissing(
                scope = "system_server:$HELPER_CLASS",
                message = "未找到 ColorOS 通知图标修正类，跳过 system_server 功能",
                cause = cause,
            )
        } ?: return
        val fixSmallIcon = Reflection.findMethodReturning(
            helperClass,
            "fixSmallIcon",
            Void.TYPE,
            Notification::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
        ) ?: run {
            diagnostics.memberMissing(
                scope = "system_server:fixSmallIcon",
                message = "未找到精确签名 fixSmallIcon(Notification, String, String, boolean): void",
            )
            return
        }

        hooks.install(fixSmallIcon, "system_server.fixSmallIcon") { chain ->
            val notification = chain.args.firstOrNull() as? Notification
            val originalIcon = notification?.smallIcon
            if (notification != null && originalIcon != null) {
                try {
                    notification.extras.putParcelable(HookContract.EXTRA_ORIGINAL_SMALL_ICON, originalIcon)
                } catch (throwable: Throwable) {
                    diagnostics.runtimeFailure(
                        scope = "system_server:preserve_original_icon",
                        message = "缓存 ColorOS 修正前的 smallIcon 失败",
                        cause = throwable,
                    )
                }
            }
            chain.proceed()
        }
    }

    private companion object {
        const val HELPER_CLASS = "com.android.server.notification.OplusNotificationFixHelper"
    }
}
