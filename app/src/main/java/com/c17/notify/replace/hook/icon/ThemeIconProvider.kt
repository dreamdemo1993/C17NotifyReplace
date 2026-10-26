package com.c17.notify.replace.hook.icon

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageItemInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.util.LruCache
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.drawable.toBitmap
import com.c17.notify.replace.diagnostics.DiagnosticEvent
import com.c17.notify.replace.diagnostics.DiagnosticLevel
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.diagnostics.OccurrencePolicy
import com.c17.notify.replace.hook.memberMissing
import com.c17.notify.replace.hook.runtimeFailure
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import kotlin.math.max

internal class ThemeIconProvider(
    private val diagnostics: Diagnostics,
    private val iconConfiguration: OplusIconConfigurationReader,
) {

    private val cache = object : LruCache<CacheKey, CacheEntry>(MAX_CACHE_SIZE_KIB) {
        override fun sizeOf(key: CacheKey, value: CacheEntry): Int =
            ((value.result as? CacheResult.Hit)?.bitmap?.allocationByteCount ?: 1)
                .let { bytes -> ((bytes + 1023) / 1024).coerceAtLeast(1) }
    }
    private val apiLock = Any()

    @Volatile
    private var api: UxIconApi? = null

    @Volatile
    private var apiResolved = false

    fun resolve(context: Context, sbn: StatusBarNotification): Bitmap? {
        val packageName = sbn.packageName?.takeIf { it.isNotBlank() } ?: return null
        return resolveByPackage(context, packageName, sbn.user, sbn.publicUserId)
    }

    /**
     * Same theme icon for the host paths that only hand over a package name: with "hide notification
     * content" on, the lock-screen hero and capsule ask for the app icon by package instead of
     * through a [StatusBarNotification], so there is no user handle to read there.
     */
    fun resolveByPackage(context: Context, packageName: String): Bitmap? =
        resolveByPackage(
            context = context,
            packageName = packageName,
            user = null,
            userId = USER_HANDLE_ALL,
        )

    private fun resolveByPackage(
        context: Context,
        packageName: String,
        user: UserHandle?,
        userId: Int,
    ): Bitmap? = try {
        val configuration = iconConfiguration.read(context)
        val key = CacheKey(
            packageName = packageName,
            userId = userId,
            uiMode = configuration.uiMode,
            themeChanged = configuration.themeChanged,
            themeChangedFlags = configuration.themeChangedFlags,
            uxIconConfig = configuration.uxIconConfig,
        )
        cachedEntry(key)?.takeIf { !it.isExpired() }?.let { entry ->
            return (entry.result as? CacheResult.Hit)?.bitmap
        }

        val bitmap = loadThemeBitmap(context, packageName, user, userId)
        val now = SystemClock.elapsedRealtime()
        putCacheEntry(
            key,
            CacheEntry(
                result = bitmap?.let(CacheResult::Hit) ?: CacheResult.Miss,
                createdAt = now,
                // A miss is almost always the theme engine still warming up, not a permanent
                // "this app has no theme icon". Ten seconds of cached miss pinned every repaint
                // inside that window to the host's fallback artwork, and the panel row reached it
                // straight after the shade opened — the row then kept the wrong icon until the
                // next post rebound it. Hits keep the long TTL; misses expire in a second, which
                // is still slow enough that the guards' per-draw retries do not hammer the engine.
                ttlMillis = if (bitmap != null) CACHE_TTL_MS else MISS_CACHE_TTL_MS,
            ),
        )
        bitmap
    } catch (exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "theme_icon:resolve",
            message = "桌面主题图标解析失败，将保留通知原始图标",
            cause = exception,
        )
        null
    }

    fun clearCache() {
        synchronized(cache) {
            cache.evictAll()
        }
    }

    private fun cachedEntry(key: CacheKey): CacheEntry? =
        synchronized(cache) { cache.get(key) }

    private fun putCacheEntry(key: CacheKey, entry: CacheEntry) {
        synchronized(cache) {
            cache.put(key, entry)
        }
    }

    /**
     * Resolves the desktop theme icon for a package, rejecting a "white sheet" answer.
     *
     * The theme engine sometimes hands back the icon *before* its background layer is ready: what
     * comes back is a plain white area (the SMS theme icon is a white bubble, the ABC one a white
     * disc) and it is cached like any other result. Once that artwork reaches the host it is frozen
     * into its own cache for the whole lifetime of the notification, so the row shows a blank white
     * squircle until SystemUI restarts — the "聚合图标又变白了" report. There is no way to tell a
     * genuinely white theme icon from a half-loaded one by looking at the pixels alone, so the
     * lookup is simply repeated once and then treated as a miss: the caller then keeps whatever
     * icon the host already had instead of pinning a blank.
     */
    private fun loadThemeBitmap(
        context: Context,
        packageName: String,
        user: UserHandle?,
        userId: Int,
    ): Bitmap? {
        val first = loadThemeBitmapOnce(context, packageName, user, userId) ?: return null
        if (!isBlankArtworkSheet(first)) return first
        val firstSpread = artworkSpreadOf(first)
        val retried = loadThemeBitmapOnce(context, packageName, user, userId)
        if (retried != null && !isBlankArtworkSheet(retried)) return retried
        diagnostics.report(
            level = DiagnosticLevel.Warning,
            event = DiagnosticEvent.ThemeIconBlank,
            message = "主题图标接口返回了纯白图块，本次保留原始图标（稍后自动重试）",
            attributes = mapOf(
                "package" to packageName,
                "retried" to (retried != null).toString(),
                "spread" to firstSpread.first.toString(),
                "mean" to firstSpread.second.toString(),
            ),
            occurrence = OccurrencePolicy.RateLimited("theme_icon:blank:$packageName", 300_000L),
        )
        return null
    }

    /** Diagnostics companion to [isBlankArtworkSheet]: the measured spread and mean luminance. */
    private fun artworkSpreadOf(bitmap: Bitmap): Pair<Double, Double> {
        val step = (minOf(bitmap.width, bitmap.height) / BLANK_SAMPLE_GRID).coerceAtLeast(1)
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        var total = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if ((pixel ushr 24) >= BLANK_OPAQUE_ALPHA) {
                    val luminance = 0.299 * ((pixel ushr 16) and 0xFF) +
                        0.587 * ((pixel ushr 8) and 0xFF) +
                        0.114 * (pixel and 0xFF)
                    if (luminance < min) min = luminance
                    if (luminance > max) max = luminance
                    total += luminance
                    count++
                }
                x += step
            }
            y += step
        }
        if (count == 0) return Pair(Double.MAX_VALUE, -1.0)
        return Pair(max - min, total / count)
    }

    private fun loadThemeBitmapOnce(
        context: Context,
        packageName: String,
        user: UserHandle?,
        userId: Int,
    ): Bitmap? {
        val packageManager = context.packageManager
        val packageInfo = packageManager.resolvePackageInfo(context, packageName, user, userId)
        if (packageInfo == null) {
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.PackageIconResolved,
                message = "主题图标解析：$packageName 读不到包信息，本次保留宿主图标",
                attributes = mapOf("package" to packageName, "stage" to "package_info"),
                occurrence = OccurrencePolicy.RateLimited("theme_icon:no_package:$packageName", 600_000L),
            )
            return null
        }
        val drawable = loadThemeDrawable(context, packageInfo.itemInfo, packageInfo.applicationInfo)
            ?: packageInfo.applicationInfo.takeUnless { it === packageInfo.itemInfo }?.let {
                loadThemeDrawable(context, it, packageInfo.applicationInfo)
            }
            // Second engine entry point: ColorOS patches `PackageItemInfo.loadIcon` for the system
            // callers in its whitelist so that it returns the *current desktop* icon, which is the
            // themed one whenever the app has a theme pack. `UxIconPackageManagerExt` sometimes
            // answers nothing for an app the launcher itself renders fine (the pdd / 天气 rows that
            // fell back to ColorOS' raw icon), so the launcher path is asked before giving up.
            ?: loadLauncherDrawable(context, packageInfo.itemInfo)
        if (drawable == null) {
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.PackageIconResolved,
                message = "主题图标解析：$packageName 主题图标接口与桌面图标入口均返回空，本次保留宿主图标",
                attributes = mapOf("package" to packageName, "stage" to "ux_icon"),
                occurrence = OccurrencePolicy.RateLimited("theme_icon:no_drawable:$packageName", 600_000L),
            )
            return null
        }
        return try {
            drawable.toIconBitmap(context)
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_icon:bitmap",
                message = "桌面主题图标位图生成失败，将保留通知原始图标",
                cause = exception,
            )
            null
        }
    }

    /**
     * True when the bitmap is one flat near-white fill: no picture at all, just the empty sheet the
     * theme engine paints before its layers are loaded. Sampled on a coarse grid — this runs on
     * every cache miss and must stay far cheaper than decoding the icon again.
     */
    private fun isBlankArtworkSheet(bitmap: Bitmap): Boolean {
        val step = (minOf(bitmap.width, bitmap.height) / BLANK_SAMPLE_GRID).coerceAtLeast(1)
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        var total = 0.0
        var count = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                // Transparent pixels carry no artwork: skipped instead of counted as white.
                if ((pixel ushr 24) >= BLANK_OPAQUE_ALPHA) {
                    val luminance = 0.299 * ((pixel ushr 16) and 0xFF) +
                        0.587 * ((pixel ushr 8) and 0xFF) +
                        0.114 * (pixel and 0xFF)
                    if (luminance < min) min = luminance
                    if (luminance > max) max = luminance
                    total += luminance
                    count++
                }
                x += step
            }
            y += step
        }
        if (count == 0) return true
        val mean = total / count
        val spread = max - min
        // A near-white low-contrast sheet counts too. The engine's half-loaded state is not always
        // a perfectly flat white: sheets with a faint tint or a pale outline (spread up to ~48,
        // mean 216..225) used to slip past the old 8/225 test, got written by the panel guards as
        // faithfully as a good icon, and the aggregate row then stayed a blank squircle no matter
        // how many repairs fired — the theme icon only ever swaps by *reference*, so a guard cannot
        // notice that its own expected drawable draws as a blank. Real desktop theme icons are
        // colourful or dark by construction; anything this pale and this flat is a sheet in
        // disguise. Refused results are never cached, so the retry ladder picks the real artwork
        // up once the engine is warm.
        return spread < BLANK_SPREAD_TOLERANCE && mean > BLANK_WHITE_LUMINANCE
    }

    private fun loadThemeDrawable(
        context: Context,
        itemInfo: PackageItemInfo,
        applicationInfo: ApplicationInfo,
    ): Drawable? {
        val uxApi = uxIconApiOrNull() ?: return null
        val uxIconManager = try {
            uxApi.constructor.newInstance(context.packageManager, context)
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_icon:api_instance",
                message = "创建 Oplus 主题图标管理器失败",
                cause = exception,
            )
            return null
        }

        return uxApi.loadItemIcon?.invokeDrawable(uxIconManager, itemInfo, applicationInfo)
            ?: uxApi.loadItemIconWithoutEdit?.invokeDrawable(uxIconManager, itemInfo, applicationInfo)
    }

    /**
     * The launcher's own answer for the app icon.
     *
     * ColorOS only serves the themed artwork to callers on its whitelist, which is why this is
     * asked from the SystemUI process and never from a notification's own context. Apps that ship
     * no theme pack simply answer with their regular icon — the caller keeps the host artwork in
     * that case, because the result goes through the same blank-sheet check as the engine.
     */
    @Suppress("DEPRECATION")
    private fun loadLauncherDrawable(context: Context, itemInfo: PackageItemInfo): Drawable? = try {
        itemInfo.loadIcon(context.packageManager)?.mutate()
    } catch (exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "theme_icon:launcher_fallback",
            message = "读取桌面图标失败，本次保留宿主图标",
            cause = exception,
        )
        null
    }

    private fun Method.invokeDrawable(
        receiver: Any,
        itemInfo: PackageItemInfo,
        applicationInfo: ApplicationInfo,
    ): Drawable? = try {
        (invoke(receiver, itemInfo, applicationInfo, true) as? Drawable)?.mutate()
    } catch (exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "theme_icon:invoke:$name",
            message = "调用 Oplus 主题图标接口失败：$name",
            cause = exception,
        )
        null
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.resolvePackageInfo(
        context: Context,
        packageName: String,
        user: UserHandle?,
        userId: Int,
    ): ResolvedPackageInfo? {
        val launcherInfo = user?.let {
            try {
                context.getSystemService(LauncherApps::class.java)
                    ?.getActivityList(packageName, it)
                    ?.firstOrNull()
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "theme_icon:launcher_info",
                    message = "读取 LauncherApps 图标入口失败，尝试应用信息",
                    cause = exception,
                )
                null
            }
        }
        val applicationInfo = launcherInfo?.applicationInfo
            ?: getApplicationInfoAsUserOrNull(packageName, userId)
            ?: getApplicationInfoOrNull(packageName)
            ?: return null
        val activityInfo = launcherInfo?.componentName
            ?.let { getActivityInfoAsUserOrNull(it, userId) ?: getActivityInfoOrNull(it) }
        return ResolvedPackageInfo(
            itemInfo = activityInfo ?: applicationInfo,
            applicationInfo = applicationInfo,
        )
    }

    private fun PackageManager.getApplicationInfoAsUserOrNull(packageName: String, userId: Int): ApplicationInfo? {
        // UserHandle.ALL (-1) has no per-user package record; the public API below resolves it.
        if (userId < 0) return null
        return invokePackageManagerMethod(
            methodName = "getApplicationInfoAsUser",
            parameterTypes = arrayOf(String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            args = arrayOf(packageName, 0, userId),
        ) as? ApplicationInfo
    }

    private fun PackageManager.getActivityInfoAsUserOrNull(componentName: ComponentName, userId: Int): ActivityInfo? {
        // Same as above: skip the per-user lookup for userless notifications.
        if (userId < 0) return null
        return invokePackageManagerMethod(
            methodName = "getActivityInfoAsUser",
            parameterTypes = arrayOf(ComponentName::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            args = arrayOf(componentName, 0, userId),
        ) as? ActivityInfo
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.getApplicationInfoOrNull(packageName: String): ApplicationInfo? = try {
        getApplicationInfo(packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.getActivityInfoOrNull(componentName: ComponentName): ActivityInfo? = try {
        getActivityInfo(componentName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun PackageManager.invokePackageManagerMethod(
        methodName: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any>,
    ): Any? = try {
        javaClass.getMethod(methodName, *parameterTypes).invoke(this, *args)
    } catch (exception: NoSuchMethodException) {
        // ColorOS 17 dropped getActivityInfoAsUser(ComponentName, int, int). The public
        // getActivityInfo(ComponentName, int) fallback is equivalent, so an absent private
        // overload is an expected generation difference, not a defect.
        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.MemberMissing,
            message = "PackageManager.$methodName 精确签名在本机不存在，使用公开 API 回退",
            cause = exception,
            attributes = mapOf("scope" to "theme_icon:package_manager:$methodName"),
            occurrence = OccurrencePolicy.Once("theme_icon:package_manager:$methodName"),
        )
        null
    } catch (exception: Exception) {
        // A userless notification (UserHandle.ALL) cannot be resolved per user; the caller falls
        // back to the public API, so a wrapped NameNotFoundException is not an error either.
        val nameNotFound = generateSequence(exception as Throwable?) { it.cause }
            .any { it is PackageManager.NameNotFoundException }
        if (nameNotFound) {
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.HookRuntimeFailed,
                message = "调用 PackageManager.$methodName 失败，使用公开 API 回退",
                cause = exception,
                attributes = mapOf("scope" to "theme_icon:package_manager:$methodName"),
                occurrence = OccurrencePolicy.Once("theme_icon:package_manager:$methodName"),
            )
        } else {
            diagnostics.runtimeFailure(
                scope = "theme_icon:package_manager:$methodName",
                message = "调用 PackageManager.$methodName 失败，使用公开 API 回退",
                cause = exception,
            )
        }
        null
    }

    private fun uxIconApiOrNull(): UxIconApi? {
        if (apiResolved) return api
        synchronized(apiLock) {
            if (!apiResolved) {
                api = buildUxIconApi()
                apiResolved = true
            }
        }
        return api
    }

    @SuppressLint("PrivateApi") // ColorOS exposes this framework extension only to system code.
    private fun buildUxIconApi(): UxIconApi? {
        val clazz = try {
            Class.forName(UX_ICON_PACKAGE_MANAGER_EXT)
        } catch (exception: ClassNotFoundException) {
            diagnostics.memberMissing(
                scope = "theme_icon:api_class",
                message = "未找到 Oplus 主题图标接口，将保留通知原始图标",
                cause = exception,
            )
            return null
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_icon:api_class",
                message = "加载 Oplus 主题图标接口失败",
                cause = exception,
            )
            return null
        }
        val constructor = try {
            clazz.getConstructor(PackageManager::class.java, Context::class.java)
        } catch (exception: NoSuchMethodException) {
            diagnostics.memberMissing(
                scope = "theme_icon:api_constructor",
                message = "Oplus 主题图标接口构造函数签名不匹配",
                cause = exception,
            )
            return null
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_icon:api_constructor",
                message = "读取 Oplus 主题图标接口构造函数失败",
                cause = exception,
            )
            return null
        }
        val loadItemIcon: Method?
        val loadItemIconWithoutEdit: Method?
        try {
            loadItemIcon = clazz.findUxIconMethod("loadItemIcon")
            loadItemIconWithoutEdit = clazz.findUxIconMethod("loadItemIconWithoutEdit")
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_icon:api_methods",
                message = "读取 Oplus 主题图标加载方法失败",
                cause = exception,
            )
            return null
        }
        if (loadItemIcon == null && loadItemIconWithoutEdit == null) {
            diagnostics.memberMissing(
                scope = "theme_icon:api_methods",
                message = "Oplus 主题图标加载方法签名不匹配",
            )
            return null
        }
        return UxIconApi(constructor, loadItemIcon, loadItemIconWithoutEdit)
    }

    private fun Class<*>.findUxIconMethod(name: String): Method? = try {
        getDeclaredMethod(
            name,
            PackageItemInfo::class.java,
            ApplicationInfo::class.java,
            Boolean::class.javaPrimitiveType!!,
        ).takeIf { Drawable::class.java.isAssignableFrom(it.returnType) }
            ?.apply { isAccessible = true }
    } catch (_: NoSuchMethodException) {
        null
    }

    // UserHandle#getIdentifier is hidden from the public SDK used by this module.
    @Suppress("DEPRECATION")
    private val StatusBarNotification.publicUserId: Int
        get() = userId

    private fun Drawable.toIconBitmap(context: Context): Bitmap {
        val requestedTargetSize = context.resources
            .getDimensionPixelSize(android.R.dimen.app_icon_size)
            .coerceAtLeast(1)
        val requestedRenderSize = max(
            requestedTargetSize,
            max(intrinsicWidth.coerceAtLeast(0), intrinsicHeight.coerceAtLeast(0)),
        )
        val renderSize = cappedSquareRenderSize(
            targetSize = requestedTargetSize,
            intrinsicWidth = intrinsicWidth,
            intrinsicHeight = intrinsicHeight,
            maxDimension = MAX_THEME_RENDER_DIMENSION,
        )
        if (requestedRenderSize > renderSize) {
            diagnostics.report(
                level = DiagnosticLevel.Warning,
                event = DiagnosticEvent.IconRenderClamped,
                message = "主题 Drawable 尺寸异常，已限制安全渲染范围",
                attributes = mapOf(
                    "limit" to MAX_THEME_RENDER_DIMENSION,
                    "requested" to requestedRenderSize,
                ),
                occurrence = OccurrencePolicy.Once("theme_icon:render_size_capped"),
            )
        }
        val rendered = toBitmap(width = renderSize, height = renderSize, config = Bitmap.Config.ARGB_8888)
        return rendered.trimTransparentPadding(requestedTargetSize.coerceAtMost(MAX_THEME_RENDER_DIMENSION))
    }

    private fun Bitmap.trimTransparentPadding(targetSize: Int): Bitmap {
        val visibleBounds = visibleBoundsOrNull() ?: return scaleToTarget(targetSize)
        val result = createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val contentSize = (targetSize * ICON_CONTENT_RATIO).toInt().coerceIn(1, targetSize)
        val scale = minOf(
            contentSize.toFloat() / visibleBounds.width().toFloat(),
            contentSize.toFloat() / visibleBounds.height().toFloat(),
        )
        val drawWidth = visibleBounds.width() * scale
        val drawHeight = visibleBounds.height() * scale
        val left = (targetSize - drawWidth) / 2f
        val top = (targetSize - drawHeight) / 2f
        Canvas(result).drawBitmap(
            this,
            visibleBounds,
            RectF(left, top, left + drawWidth, top + drawHeight),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return result
    }

    private fun Bitmap.scaleToTarget(targetSize: Int): Bitmap {
        if (width == targetSize && height == targetSize) return this
        val result = createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(
            this,
            null,
            RectF(0f, 0f, targetSize.toFloat(), targetSize.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return result
    }

    private fun Bitmap.visibleBoundsOrNull(): Rect? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (Color.alpha(this[x, y]) <= OPAQUE_ALPHA_THRESHOLD) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return null
        return Rect(left, top, right + 1, bottom + 1)
    }

    private fun CacheEntry.isExpired(): Boolean =
        SystemClock.elapsedRealtime() - createdAt > ttlMillis

    private data class ResolvedPackageInfo(
        val itemInfo: PackageItemInfo,
        val applicationInfo: ApplicationInfo,
    )

    private data class UxIconApi(
        val constructor: Constructor<*>,
        val loadItemIcon: Method?,
        val loadItemIconWithoutEdit: Method?,
    )

    private data class CacheKey(
        val packageName: String,
        val userId: Int,
        val uiMode: Int,
        val themeChanged: Long,
        val themeChangedFlags: Long,
        val uxIconConfig: Long,
    )

    private data class CacheEntry(
        val result: CacheResult,
        val createdAt: Long,
        val ttlMillis: Long = CACHE_TTL_MS,
    )

    private sealed class CacheResult {
        data class Hit(val bitmap: Bitmap) : CacheResult()
        object Miss : CacheResult()
    }

    private companion object {
        const val UX_ICON_PACKAGE_MANAGER_EXT = "android.app.UxIconPackageManagerExt"
        const val ICON_CONTENT_RATIO = 1f
        const val OPAQUE_ALPHA_THRESHOLD = 8
        const val CACHE_TTL_MS = 10_000L

        /** Misses are retried almost at once: the engine warms up within a frame or two. */
        const val MISS_CACHE_TTL_MS = 1_000L
        const val MAX_CACHE_SIZE_KIB = 4 * 1024
        const val MAX_THEME_RENDER_DIMENSION = 512
        /**
         * `UserHandle.ALL`: no [StatusBarNotification] means no user handle is available, and the
         * lookups then fall back to the user-independent public PackageManager API — the same path
         * SystemUI's own notifications take.
         */
        const val USER_HANDLE_ALL = -1

        /** Samples per axis for the "is this a blank sheet" check. */
        const val BLANK_SAMPLE_GRID = 16

        /** Alpha below which a pixel counts as transparent when judging a theme icon. */
        const val BLANK_OPAQUE_ALPHA = 32

        /** Luminance spread below which the artwork is one flat fill instead of a picture. */
        const val BLANK_SPREAD_TOLERANCE = 48.0

        /** Mean luminance above which that flat fill is white, i.e. the failure this check targets. */
        const val BLANK_WHITE_LUMINANCE = 216.0
    }
}
