package com.c17.notify.replace.hook.icon

import android.content.Context
import android.content.res.Configuration
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.hook.memberMissing
import com.c17.notify.replace.hook.runtimeFailure
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal data class OplusIconConfiguration(
    val uiMode: Int,
    val themeChanged: Long,
    val themeChangedFlags: Long,
    val uxIconConfig: Long,
)

/**
 * Reads the Oplus theme-state counters off a [Configuration].
 *
 * The reflective lookups are resolved once per class and reused: `getMethod`/`getDeclaredField`
 * scan the whole class on every call, and [read] runs on *every* theme-icon resolve — including
 * cache hits — i.e. on every bound cell while the app strip is scrolling. That reflection churn
 * was the scroll jank. The *values* are still read live on each call, so a theme switch is picked
 * up immediately; only the lookup cost is cached.
 */
internal class OplusIconConfigurationReader(
    private val diagnostics: Diagnostics,
) {

    private val extraAccessors = HashMap<Class<*>, ExtraAccessor?>()
    private val longAccessors = HashMap<Class<*>, LongAccessor?>()

    fun read(context: Context): OplusIconConfiguration {
        val configuration = context.resources.configuration
        val extra = configuration.oplusExtraConfigurationOrNull()
        return OplusIconConfiguration(
            uiMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
            themeChanged = extra?.readLong("mThemeChanged", "getThemeChanged") ?: 0L,
            themeChangedFlags = extra?.readLong("mThemeChangedFlags", "getThemeChangedFlags") ?: 0L,
            uxIconConfig = extra?.readLong("mUxIconConfig", "getUxIconConfig") ?: -1L,
        )
    }

    /** Either the accessor method or the backing field, resolved once per Configuration class. */
    private class ExtraAccessor(val method: Method?, val field: Field?) {
        fun read(target: Configuration): Any? {
            val accessor = method
            if (accessor != null) return runCatching { accessor.invoke(target) }.getOrNull()
            val backing = field
            if (backing != null) return runCatching { backing.get(target) }.getOrNull()
            return null
        }
    }

    private fun Configuration.oplusExtraConfigurationOrNull(): Any? {
        val accessor = synchronized(extraAccessors) {
            extraAccessors.getOrPutCached(javaClass) { resolveExtraAccessor(javaClass) }
        } ?: return null
        return accessor.read(this)
    }

    private fun resolveExtraAccessor(owner: Class<*>): ExtraAccessor? {
        val method = try {
            val candidate = owner.getMethod("getOplusExtraConfiguration")
            if (!Modifier.isStatic(candidate.modifiers) && candidate.returnType != Void.TYPE) {
                candidate
            } else {
                diagnostics.memberMissing(
                    scope = "icon_configuration:extra_accessor",
                    message = "Oplus 图标配置访问器签名不匹配，尝试字段回退",
                )
                null
            }
        } catch (_: NoSuchMethodException) {
            null
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "icon_configuration:extra_accessor",
                message = "读取 Oplus 图标配置失败，尝试字段回退",
                cause = exception,
            )
            null
        }
        if (method != null) return ExtraAccessor(method, field = null)
        val field = try {
            owner.getDeclaredField("mOplusExtraConfiguration").apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            diagnostics.memberMissing(
                scope = "icon_configuration:extra_field",
                message = "未找到 Oplus 图标配置成员，使用安全默认值",
            )
            null
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "icon_configuration:extra_field",
                message = "读取 Oplus 图标配置字段失败，使用安全默认值",
                cause = exception,
            )
            null
        }
        return field?.let { ExtraAccessor(method = null, field = it) }
    }

    /** Either the backing field or its accessor, resolved once per extra-configuration class. */
    private class LongAccessor(val field: Field?, val method: Method?) {
        fun read(target: Any): Long? {
            val backing = field
            if (backing != null) return runCatching { (backing.get(target) as? Number)?.toLong() }.getOrNull()
            val accessor = method
            if (accessor != null) return runCatching { (accessor.invoke(target) as? Number)?.toLong() }.getOrNull()
            return null
        }
    }

    private fun Any.readLong(fieldName: String, methodName: String): Long? {
        val accessor = synchronized(longAccessors) {
            longAccessors.getOrPutCached(javaClass) { resolveLongAccessor(javaClass, fieldName, methodName) }
        } ?: return null
        return accessor.read(this)
    }

    private fun resolveLongAccessor(owner: Class<*>, fieldName: String, methodName: String): LongAccessor? {
        val field = try {
            owner.getDeclaredField(fieldName).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            // Try the known accessor below.
            null
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "icon_configuration:$fieldName",
                message = "读取 Oplus 图标配置字段失败，尝试访问器",
                cause = exception,
            )
            null
        }
        if (field != null) return LongAccessor(field, method = null)
        val method = try {
            val candidate = owner.getMethod(methodName)
            candidate.takeIf {
                it.returnType == Long::class.javaPrimitiveType || Number::class.java.isAssignableFrom(it.returnType)
            } ?: run {
                diagnostics.memberMissing(
                    scope = "icon_configuration:$methodName",
                    message = "未找到 Oplus 图标配置成员，使用安全默认值",
                )
                null
            }
        } catch (_: NoSuchMethodException) {
            diagnostics.memberMissing(
                scope = "icon_configuration:$methodName",
                message = "未找到 Oplus 图标配置成员，使用安全默认值",
            )
            null
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "icon_configuration:$methodName",
                message = "读取 Oplus 图标配置访问器失败，使用安全默认值",
                cause = exception,
            )
            null
        }
        return method?.let { LongAccessor(field = null, method = it) }
    }

    /** [getOrPut] recomputes whenever the stored value is null; these caches legitimately store null. */
    private inline fun <K, V> MutableMap<K, V>.getOrPutCached(key: K, value: () -> V): V =
        if (containsKey(key)) get(key) as V else value().also { put(key, it) }
}
