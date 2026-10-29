package com.c17.notify.replace.hook.games

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import com.c17.notify.replace.diagnostics.DiagnosticEvent
import com.c17.notify.replace.diagnostics.DiagnosticLevel
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.diagnostics.OccurrencePolicy
import com.c17.notify.replace.hook.HookRegistrar
import com.c17.notify.replace.hook.icon.OplusIconConfigurationReader
import com.c17.notify.replace.hook.icon.ThemeIconProvider
import com.c17.notify.replace.hook.memberMissing
import com.c17.notify.replace.hook.reflect.Reflection
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Theme-icon support for the OPPO game assistant (`com.oplus.games`).
 *
 * The assistant draws three separate copies of an app's launcher icon while a game is running, and
 * none of them go through SystemUI:
 *
 * - the dock / edge panel app icons — `ExternalApplicationManager#u(String)` feeding a Glide
 *   loader (now `business.secondarypanel.manager.a#f(String)`), bound to the cell by
 *   [EDGE_CELL]`#c(model, position)` (now `V`);
 * - the "弹幕" chat bubble icon — [BARRAGE_MANAGER]`#loadNotificationIcon(Icon, String, Integer)`;
 * - the app mark next to a contact avatar in the message box —
 *   `MessageNotificationBoxManager#Y(ImageView, MessageData)`.
 *
 * The launcher-icon hook is the widest of the four and the one that actually catches everything:
 * every `PackageItemInfo#loadIcon` call inside this process answers with the theme icon, so paths
 * that cache a `Drawable` (or replay Glide's disk cache) never see the plain artwork at all.
 */
internal class GameAssistantRuntime(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val processName: String,
) {

    fun install(classLoader: ClassLoader) {
        // The theme icon comes from a framework-only API that Android lists as hidden; the module
        // already does the same exemption for its SystemUI runtime.
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(THEME_ICON_FRAMEWORK_CLASS)
        }
        val iconConfiguration = OplusIconConfigurationReader(diagnostics)
        val replacer = GameIconReplacer(
            diagnostics = diagnostics,
            themeIcons = ThemeIconProvider(diagnostics, iconConfiguration),
            application = GameProcessApplication::current,
        )

        installLauncherIconHook(classLoader, replacer)
        installEdgePanelIcons(classLoader, replacer)
        installEdgePanelCells(classLoader, replacer)
        installBarrageIcon(classLoader, replacer)
        installMessageBadge(classLoader, replacer)
        probeThemeIconApi(replacer)

        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.HookInstalled,
            message = "游戏助手主题图标钩子已就绪",
            attributes = mapOf("process" to processName),
            occurrence = OccurrencePolicy.Once("games:ready:$processName"),
        )
    }

    /**
     * The theme API is documented for system processes only, so whether this app's uid may use it is
     * decided at runtime. One probe shortly after start records the answer in the release log
     * (`games.icon_replaced` / `games.icon_skipped` / `games.icon_selfcheck`) instead of leaving a
     * silent no-op.
     */
    private fun probeThemeIconApi(replacer: GameIconReplacer) {
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    runCatching {
                        replacer.drawableFor(SITE_PROBE, PROBE_PACKAGE)
                        replacer.selfCheck(PROBE_PACKAGE)
                    }
                },
                PROBE_DELAY_MILLIS,
            )
        }
    }

    /**
     * `PackageItemInfo#loadIcon` is the single place a launcher icon is decoded in this process:
     * `PackageManager#getApplicationIcon`, `LauncherApps` entries and the assistant's own
     * `ExternalApplicationManager#u` all end up here. Answering with the theme icon covers every
     * row that draws an app icon, including the ones that keep the `Drawable` around.
     */
    private fun installLauncherIconHook(classLoader: ClassLoader, replacer: GameIconReplacer) {
        val itemInfo = Reflection.loadClassOrNull("android.content.pm.PackageItemInfo", classLoader) { cause ->
            diagnostics.memberMissing(
                scope = "games:package_item_info",
                message = "未找到 PackageItemInfo，跳过应用图标替换",
                cause = cause,
            )
        } ?: return
        val method = Reflection.findMethod(itemInfo, "loadIcon", PackageManager::class.java) ?: run {
            diagnostics.memberMissing(
                scope = "games:package_item_info_load_icon",
                message = "未找到 PackageItemInfo.loadIcon(PackageManager)，跳过应用图标替换",
            )
            return
        }
        hooks.install(method, "games.launcher_icon") { chain ->
            val item = chain.instanceOrNull
            // Only application-level icons: a service or permission entry has no themed artwork and
            // resolving one would just cost a lookup.
            val appLevel = item is ApplicationInfo || item is ActivityInfo
            val packageName = (item as? android.content.pm.PackageItemInfo)?.packageName
            (if (appLevel) replacer.drawableFor(SITE_LAUNCHER_ICON, packageName) else null)
                ?: chain.proceed()
        }
    }

    /**
     * Dock / edge-panel app icons: `(String): Drawable`.
     *
     * 10.43.7 turned this class into `business.secondarypanel.manager.a` and renamed the method to
     * `f`; the signature is what the lookup anchors on now, see [GameAssistantMembers].
     */
    private fun installEdgePanelIcons(classLoader: ClassLoader, replacer: GameIconReplacer) {
        val target = GameAssistantMembers.edgePanelIconLoader(classLoader) ?: run {
            diagnostics.memberMissing(
                scope = "games:edge_panel_method",
                message = "未找到游戏助手应用图标管理器 (String)->Drawable，跳过应用栏图标替换",
            )
            return
        }
        reportResolved(SITE_EDGE_PANEL, target.label)
        hooks.install(target.method, "games.edge_panel.app_icon") { chain ->
            val packageName = chain.args.getOrNull(0) as? String
            replacer.drawableFor(SITE_EDGE_PANEL, packageName) ?: chain.proceed()
        }
    }

    /**
     * The dock cell binds its icon through Glide, whose disk cache still holds the pre-module
     * picture — the loader is never called again for a package that was already displayed, so
     * replacing `u(String)` alone leaves the cached bitmap on screen. Binding is the moment the
     * cell reveals which package it shows, so the themed icon is written there and kept by
     * [GameIconTakeovers] until Glide's cached result has landed.
     */
    private fun installEdgePanelCells(classLoader: ClassLoader, replacer: GameIconReplacer) {
        val cell = Reflection.loadClassOrNull(EDGE_CELL, classLoader) { cause ->
            diagnostics.memberMissing(
                scope = "games:edge_cell_class",
                message = "未找到游戏助手应用图标卡片，跳过应用栏图标接管",
                cause = cause,
            )
        } ?: return
        val bind = GameAssistantMembers.cellBind(cell) ?: run {
            diagnostics.memberMissing(
                scope = "games:edge_cell_bind",
                message = "未找到 GameAppCellView 的绑定方法 (model, int)，跳过应用栏图标接管",
            )
            return
        }
        reportResolved(SITE_EDGE_PANEL_CELL, cell.name + "#" + bind.name)
        val iconGetter = Reflection.findMethod(cell, "getIcon")
        hooks.install(bind, "games.edge_panel.cell") { chain ->
            val result = chain.proceed()
            runCatching {
                val packageName = packageFromModel(chain.args.getOrNull(0), replacer)
                val icon = iconGetter?.invoke(chain.instanceOrNull) as? ImageView
                val themed = packageName?.let { replacer.drawableFor(SITE_EDGE_PANEL, it) }
                if (icon != null && themed != null) {
                    GameIconTakeovers.take(icon, themed)
                }
            }
            result
        }
    }

    /**
     * "弹幕" bubble icon: the host loads the notification's small icon and falls back to a bundled
     * resource id. Replacing the result keeps the bubble's text/avatar layout untouched.
     */
    private fun installBarrageIcon(classLoader: ClassLoader, replacer: GameIconReplacer) {
        val manager = Reflection.loadClassOrNull(BARRAGE_MANAGER, classLoader) { cause ->
            diagnostics.memberMissing(
                scope = "games:barrage_class",
                message = "未找到游戏助手弹幕管理器，跳过弹幕图标替换",
                cause = cause,
            )
        } ?: return
        val method = Reflection.findMethod(
            manager,
            "loadNotificationIcon",
            Icon::class.java,
            String::class.java,
            Integer::class.java,
        ) ?: run {
            diagnostics.memberMissing(
                scope = "games:barrage_method",
                message = "未找到 BarrageManager.loadNotificationIcon，跳过弹幕图标替换",
            )
            return
        }
        hooks.install(method, "games.barrage.notification_icon") { chain ->
            val packageName = chain.args.getOrNull(1) as? String
            replacer.drawableFor(SITE_BARRAGE, packageName) ?: chain.proceed()
        }
    }

    /**
     * App mark next to the contact avatar in the message box: the host sets a bundled icon resource
     * looked up from the package name.
     *
     * 10.43.7 renamed `MessageNotificationBoxManager` and the `Y(ImageView, MessageData)` entry
     * point is gone from that shape; the lookup keeps trying, and the log says which one it found
     * or that it found none — the mark then falls back to the host's own artwork.
     */
    private fun installMessageBadge(classLoader: ClassLoader, replacer: GameIconReplacer) {
        val messageData = Reflection.loadClassOrNull(MESSAGE_DATA, classLoader) { cause ->
            diagnostics.memberMissing(
                scope = "games:message_data_class",
                message = "未找到游戏助手消息数据模型，跳过消息角标替换",
                cause = cause,
            )
        } ?: return
        val target = GameAssistantMembers.messageBadgeBinder(classLoader, messageData) ?: run {
            diagnostics.memberMissing(
                scope = "games:message_badge_method",
                message = "未找到消息盒子的角标方法 (ImageView, MessageData)，跳过消息角标替换",
            )
            return
        }
        reportResolved(SITE_MESSAGE_BADGE, target.label)
        hooks.install(target.method, "games.message_box.app_mark") { chain ->
            val view = chain.args.getOrNull(0) as? ImageView
            val packageName = notificationPackage(chain.args.getOrNull(1))
            val themed = view?.let { replacer.drawableFor(SITE_MESSAGE_BADGE, packageName) }
            // The host also hides the mark for group conversations, so its own call must still run.
            // It loads the bundled icon through Glide, hence the pending request is cancelled before
            // the theme icon is written — and kept by the takeover, because that request can still
            // deliver after the clear.
            val result = chain.proceed()
            if (view != null && themed != null) {
                clearGlideRequest(view, classLoader)
                GameIconTakeovers.take(view, themed)
            }
            result
        }
    }

    /**
     * Records which class/method a name-independent lookup settled on: the assistant re-obfuscates
     * its `business.*` packages on most releases, and without this line a future miss is invisible.
     */
    private fun reportResolved(site: String, label: String) {
        diagnostics.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.GameAssistantHookResolved,
            message = "游戏助手钩子目标已按签名定位",
            attributes = mapOf("site" to site, "target" to label),
            occurrence = OccurrencePolicy.Once("games:resolved:$site"),
        )
    }

    /**
     * `Glide.with(view).clear(view)`: cancels the asynchronous load the host had just started.
     * Reflection keeps the module free of a compile-time Glide dependency.
     */
    private fun clearGlideRequest(view: ImageView, classLoader: ClassLoader) {
        runCatching {
            val glide = Class.forName("com.bumptech.glide.Glide", false, classLoader)
            val manager = glide.getMethod("with", View::class.java).invoke(null, view)
            manager?.javaClass?.getMethod("clear", View::class.java)?.invoke(manager, view)
        }
    }

    /**
     * The dock cell's model carries a `business.gamedock.state.a` whose `m` is the package name.
     * Both the accessors and the field names are re-obfuscated every release, so the candidates are
     * collected from the model's shape and the first one the theme engine actually knows wins.
     */
    private fun packageFromModel(model: Any?, replacer: GameIconReplacer): String? {
        val data = model ?: return null
        val candidates = LinkedHashSet<String>()
        // 10.42.6 shape: model.r().B()
        runCatching {
            val state = data.javaClass.getMethod("r").invoke(data)
            state?.javaClass?.getMethod("B")?.invoke(state) as? String
        }.getOrNull()?.let(candidates::add)
        // 10.43.7 shape: model.<state>.<String>, the state type name is not obfuscated.
        data.javaClass.declaredFields
            .filter { field -> field.type.name.startsWith(GAME_STATE_PACKAGE) }
            .mapNotNull { field -> field.readOrNull(data) }
            .forEach { state ->
                state.javaClass.declaredFields
                    .filter { field -> field.type == String::class.java }
                    .mapNotNull { field -> field.readOrNull(state) as? String }
                    .forEach(candidates::add)
            }
        // Anything else on the model that looks like a package name.
        data.javaClass.declaredFields
            .filter { field -> field.type == String::class.java }
            .mapNotNull { field -> field.readOrNull(data) as? String }
            .filter { value -> PACKAGE_SHAPE.matches(value) }
            .forEach(candidates::add)
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { replacer.hasThemeIcon(it) } ?: candidates.first()
    }

    private fun java.lang.reflect.Field.readOrNull(target: Any): Any? = runCatching {
        isAccessible = true
        get(target)
    }.getOrNull()

    private fun notificationPackage(messageData: Any?): String? {
        val data = messageData ?: return null
        return runCatching {
            val sbn = data.javaClass.getMethod("getSub").invoke(data) ?: return@runCatching null
            sbn.javaClass.getMethod("getPackageName").invoke(sbn) as? String
        }.getOrNull()
    }

    private companion object {
        const val EDGE_CELL = "business.edgepanel.components.widget.view.GameAppCellView"
        const val BARRAGE_MANAGER = "com.oplus.feature.barragenotification.BarrageManager"
        const val MESSAGE_DATA = "business.mainpanel.welfare.MessageData"
        const val THEME_ICON_FRAMEWORK_CLASS = "Landroid/app/UxIconPackageManagerExt;"

        /** Prefix of the dock cell's state object; the concrete class name survived 10.43.7. */
        const val GAME_STATE_PACKAGE = "business.gamedock."

        val PACKAGE_SHAPE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*){1,}")

        const val SITE_LAUNCHER_ICON = "launcher_icon"
        const val SITE_EDGE_PANEL = "edge_panel"
        const val SITE_EDGE_PANEL_CELL = "edge_panel_cell"
        const val SITE_BARRAGE = "barrage"
        const val SITE_MESSAGE_BADGE = "message_badge"
        const val SITE_PROBE = "probe"

        /** Always installed and always has a themed icon, so a miss means "theme API unavailable". */
        const val PROBE_PACKAGE = "com.android.settings"
        const val PROBE_DELAY_MILLIS = 3_000L
    }
}

/** The game assistant has no injectable context, so its [android.app.Application] is looked up once. */
internal object GameProcessApplication {

    @Volatile
    private var cached: Context? = null

    fun current(): Context? = cached ?: runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val application = activityThread.getMethod("currentApplication").invoke(null) as? Context
        application?.also { cached = it }
    }.getOrNull()
}
