package com.c17.notify.replace.hook.games

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
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

    /** One row-binder hook per binder class: the list re-registers on every fragment rebuild. */
    private val messageBadgeBinds: MutableSet<java.lang.reflect.Method> =
        java.util.concurrent.ConcurrentHashMap.newKeySet<java.lang.reflect.Method>()

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
     * App mark next to the contact avatar in the message box.
     *
     * 10.43.7 removed `MessageNotificationBoxManager#Y(ImageView, MessageData)`: the box now binds
     * through the commonui multitype list, and the mark is a *bundled* drawable resource chosen by
     * package name (`business.mainpanel.welfare.a#h(String)`) and loaded with Glide. Nothing on that
     * path carries the row's `ImageView`, so the row binder is hooked instead and the mark is
     * overwritten after the bind.
     */
    private fun installMessageBadge(classLoader: ClassLoader, replacer: GameIconReplacer) {
        val messageData = Reflection.loadClassOrNull(MESSAGE_DATA, classLoader) { cause ->
            diagnostics.memberMissing(
                scope = "games:message_data_class",
                message = "未找到游戏助手消息数据模型，跳过消息角标替换",
                cause = cause,
            )
        } ?: return
        // The list is registered through the vendor's multitype adapter: every data class gets an
        // entry `(dataClass, binder, listener)`. Hooking that entry is how the message box's binder
        // is reached without knowing its (obfuscated) class name.
        val multitype = Reflection.loadClassOrNull(MULTITYPE_BUILDER, classLoader) { cause ->
            diagnostics.memberMissing(
                scope = "games:message_badge_multitype",
                message = "未找到游戏助手列表注册器，跳过消息角标替换",
                cause = cause,
            )
        } ?: return
        val entryClass = Reflection
            .findMethodReturning(multitype, MULTITYPE_ENTRY_GETTER, null, Int::class.javaPrimitiveType)
            ?.returnType ?: run {
                diagnostics.memberMissing(
                    scope = "games:message_badge_entry",
                    message = "未找到游戏助手列表条目类型，跳过消息角标替换",
                )
                return
            }
        val entryConstructor = entryClass.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterCount == 3 &&
                candidate.parameterTypes[0] == Class::class.java &&
                !candidate.parameterTypes[1].name.startsWith(ANDROID_PACKAGE_PREFIX) &&
                candidate.parameterTypes[2] != Class::class.java
        } ?: run {
            diagnostics.memberMissing(
                scope = "games:message_badge_entry_ctor",
                message = "未找到游戏助手列表条目构造器，跳过消息角标替换",
            )
            return
        }
        hooks.install(entryConstructor, "games.message_box.register") { chain ->
            val dataType = chain.args.getOrNull(0) as? Class<*>
            val binder = chain.args.getOrNull(1)
            val result = chain.proceed()
            if (dataType == messageData && binder != null) {
                // Installed off the constructor callback: hooking a method from inside another
                // hook's dispatch is legal but needlessly fragile.
                Handler(Looper.getMainLooper()).post {
                    runCatching { hookMessageRowBinder(binder, messageData, replacer, classLoader) }
                }
            }
            result
        }
    }

    /**
     * Hooks the binder that draws one message row.
     *
     * The binder is shared by several row types (`ci` in 10.43.7), so the callback first checks the
     * data class. Its bind method is `(position: Int, holder: ViewHolder, data: Any?)`. The holder
     * type is matched by shape, not by name: this APK is R8-processed down to `androidx` too, so
     * `RecyclerView$ViewHolder` exists only as `androidx.recyclerview.widget.d0` here — the module's
     * own copy of that class would never be assignable to it.
     */
    private fun hookMessageRowBinder(
        binder: Any,
        messageData: Class<*>,
        replacer: GameIconReplacer,
        classLoader: ClassLoader,
    ) {
        val bind = messageBindMethod(binder.javaClass) ?: run {
            diagnostics.memberMissing(
                scope = "games:message_badge_bind",
                message = "未找到消息行的绑定方法 (Int, ViewHolder, Any)，跳过消息角标替换",
            )
            return
        }
        synchronized(messageBadgeBinds) {
            if (!messageBadgeBinds.add(bind)) return
        }
        reportResolved(SITE_MESSAGE_BADGE, binder.javaClass.name + "#" + bind.name)
        hooks.install(bind, "games.message_box.row") { chain ->
            val holder = chain.args.getOrNull(1)
            val data = chain.args.getOrNull(2)
            val result = chain.proceed()
            if (data != null && messageData.isInstance(data)) {
                runCatching { applyMessageBadge(holder, data, replacer, classLoader) }
            }
            result
        }
    }

    /**
     * The host builds its mark from a *bundled* drawable resource keyed by package name
     * (`business.mainpanel.welfare.a#h(String)`), so the picture never goes through
     * `PackageManager` and the launcher-icon hook cannot see it. The row's views are the only place
     * the theme icon can be written, and the mark is the small round view next to the avatar.
     */
    private fun applyMessageBadge(
        holder: Any?,
        data: Any,
        replacer: GameIconReplacer,
        classLoader: ClassLoader,
    ) {
        val root = itemViewOf(holder) ?: return
        val packageName = notificationPackage(data) ?: return
        val themed = replacer.drawableFor(SITE_MESSAGE_BADGE, packageName) ?: return
        // A row is bound while it is still unmeasured: every child reports 0×0 until the adapter's
        // layout pass runs, and the mark is picked by size. Hence the retries.
        retryMessageBadge(root, packageName, themed, classLoader, 0)
    }

    private fun retryMessageBadge(
        root: View,
        packageName: String,
        themed: Drawable,
        classLoader: ClassLoader,
        attempt: Int,
    ) {
        val badge = findMessageBadge(root)
        if (badge != null) {
            clearGlideRequest(badge, classLoader)
            GameIconTakeovers.take(badge, themed, MESSAGE_BADGE_WINDOW_MILLIS)
            reportBadgeApplied(packageName, badge)
            return
        }
        if (attempt >= BADGE_RETRY_DELAYS.size || (attempt > 0 && !root.isAttachedToWindow)) {
            reportBadgeMiss(root, packageName)
            return
        }
        root.postDelayed(
            { runCatching { retryMessageBadge(root, packageName, themed, classLoader, attempt + 1) } },
            BADGE_RETRY_DELAYS[attempt],
        )
    }

    /** `RecyclerView.ViewHolder#itemView`, read reflectively for the reason above. */
    private fun itemViewOf(holder: Any?): View? {
        if (holder == null) return null
        var current: Class<*>? = holder.javaClass
        while (current != null) {
            val field = runCatching { current.getDeclaredField(ITEM_VIEW_FIELD) }.getOrNull()
            if (field != null && View::class.java.isAssignableFrom(field.type)) {
                return runCatching {
                    field.isAccessible = true
                    field.get(holder) as? View
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    /**
     * `(position: Int, holder: <any class with an itemView>, data: Object): void` — the shape every
     * row binder of this list shares.
     */
    private fun messageBindMethod(owner: Class<*>): java.lang.reflect.Method? {
        var current: Class<*>? = owner
        while (current != null && current != Any::class.java) {
            val methods = current.declaredMethods
            for (method in methods) {
                if (method.parameterCount != 3) continue
                if (method.parameterTypes[0] != Int::class.javaPrimitiveType) continue
                if (method.parameterTypes[2] != Any::class.java) continue
                if (method.returnType != Void.TYPE) continue
                if (java.lang.reflect.Modifier.isStatic(method.modifiers)) continue
                if (!hasItemView(method.parameterTypes[1])) continue
                return method
            }
            current = current.superclass
        }
        return null
    }

    private fun hasItemView(candidate: Class<*>): Boolean {
        var current: Class<*>? = candidate
        while (current != null) {
            if (runCatching { current.getDeclaredField(ITEM_VIEW_FIELD) }.isSuccess) return true
            current = current.superclass
        }
        return false
    }

    /**
     * The row stacks two `COUIRoundImageView`s: a 32dp app icon filled from the bundled
     * `welfare.a#h(package)` resource — the plain artwork this module replaces — and a 16dp view
     * over its top-right corner that carries the notification's `largeIcon` for group chats.
     *
     * So the icon is the *larger* one, and the 16dp corner view is deliberately out of the size
     * window: replacing it would paint the app icon over a contact's picture. Group rows hide the
     * 32dp icon altogether, leaving nothing to do.
     */
    private fun findMessageBadge(root: View): ImageView? {
        val marks = ArrayList<MarkCandidate>(8)
        collectMarkViews(root, marks, 0)
        val measured = marks.filter { it.round && it.visible && it.widthDp > 0 && it.heightDp > 0 }
        if (measured.isEmpty()) return null
        val icon = measured.maxByOrNull { maxOf(it.widthDp, it.heightDp) } ?: return null
        return icon.view.takeIf { icon.markSized }
    }

    private fun collectMarkViews(view: View, out: MutableList<MarkCandidate>, depth: Int) {
        if (depth > MAX_MARK_DEPTH) return
        if (view is ImageView) {
            out.add(MarkCandidate.of(view))
        }
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                collectMarkViews(view.getChildAt(index), out, depth + 1)
            }
        }
    }

    private fun reportBadgeApplied(packageName: String, badge: ImageView) {
        val mark = MarkCandidate.of(badge)
        diagnostics.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.GameAssistantIconReplaced,
            message = "消息盒子角标已替换为主题图标",
            attributes = mapOf(
                "package" to packageName,
                "site" to SITE_MESSAGE_BADGE,
                "stage" to "message_badge_view",
                "target" to mark.describe(),
            ),
            occurrence = OccurrencePolicy.Once("games:badge_applied:" + badge.id),
        )
    }

    private fun reportBadgeMiss(root: View, packageName: String) {
        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.PackageIconResolved,
            message = "消息盒子角标：行内没有可识别的小图，本次保留宿主图标",
            attributes = mapOf(
                "package" to packageName,
                "stage" to "message_badge_view",
                "row" to root.javaClass.simpleName + "/" + root.childCount,
                "views" to describeMarks(root),
            ),
            occurrence = OccurrencePolicy.RateLimited("games:badge_miss", 30_000L),
        )
    }

    /** Compact inventory of the row's images, so a future miss is diagnosable from one log line. */
    private fun describeMarks(root: View): String {
        val marks = ArrayList<MarkCandidate>(8)
        collectMarkViews(root, marks, 0)
        if (marks.isEmpty()) return "none"
        return marks.take(MAX_REPORTED_MARKS).joinToString("|") { it.describe() }
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
     * `Glide.with(context).clear(view)`: cancels the asynchronous load the host had just started.
     *
     * Both the entry point and its `with` are renamed by R8 in this APK (`com.bumptech.glide.a#f`),
     * so the class is tried by its well-known name first and the call is then resolved by shape —
     * a static `(Context) -> RequestManager` and an instance `(View) -> void`. Reflection keeps the
     * module free of a compile-time Glide dependency.
     */
    private fun clearGlideRequest(view: ImageView, classLoader: ClassLoader) {
        runCatching {
            val glide = GLIDE_ENTRY_POINTS.firstNotNullOfOrNull { name ->
                runCatching { Class.forName(name, false, classLoader) }.getOrNull()
            } ?: return@runCatching
            val with = glide.methods.firstOrNull { method ->
                java.lang.reflect.Modifier.isStatic(method.modifiers) &&
                    method.parameterCount == 1 &&
                    method.parameterTypes[0] == Context::class.java &&
                    method.returnType != Void.TYPE
            } ?: return@runCatching
            val manager = with.invoke(null, view.context) ?: return@runCatching
            val clear = manager.javaClass.methods.firstOrNull { method ->
                method.parameterCount == 1 &&
                    method.parameterTypes[0] == View::class.java &&
                    method.returnType == Void.TYPE
            } ?: return@runCatching
            clear.invoke(manager, view)
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

        /** Vendor multitype list: holds `(dataClass, binder, listener)` entries for every row type. */
        const val MULTITYPE_BUILDER = "com.oplus.commonui.multitype.b"
        const val MULTITYPE_ENTRY_GETTER = "b"

        const val ANDROID_PACKAGE_PREFIX = "android."

        /** RecyclerView's holder field; the class itself is obfuscated in this APK. */
        const val ITEM_VIEW_FIELD = "itemView"

        /**
         * The row is bound before it is measured — children are 0×0 at bind time — so the mark only
         * becomes findable after the adapter's layout pass. Cumulative ≈ 0.9 s.
         */
        val BADGE_RETRY_DELAYS = longArrayOf(32L, 96L, 240L, 480L)
        const val MAX_MARK_DEPTH = 6
        const val MAX_REPORTED_MARKS = 6

        /** Glide delivers the host's bundled mark a few frames later, so the guard outlives the bind. */
        const val MESSAGE_BADGE_WINDOW_MILLIS = 10_000L

        /** Glide's entry point: the library name, then the R8-renamed one this APK ships. */
        val GLIDE_ENTRY_POINTS = arrayOf("com.bumptech.glide.Glide", "com.bumptech.glide.a")

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

/** One `ImageView` of a message row, measured once so repeated retries stay cheap. */
internal class MarkCandidate private constructor(
    val view: ImageView,
    val widthDp: Int,
    val heightDp: Int,
    val visible: Boolean,
    private val idName: String,
) {

    /** The mark is a `COUIRoundImageView`; the shield icon and dividers are plain views. */
    val round: Boolean = view.javaClass.name.contains(ROUND_IMAGE_VIEW)

    /** The row's app icon is 32dp; the contact picture over its corner is 16dp. */
    val markSized: Boolean
        get() {
            val longest = maxOf(widthDp, heightDp)
            val shortest = minOf(widthDp, heightDp)
            return longest in MARK_MIN_DP..MARK_MAX_DP &&
                longest - shortest <= MARK_SQUARE_TOLERANCE_DP
        }

    fun describe(): String {
        val name = if (idName.length > MAX_ID_NAME) idName.take(MAX_ID_NAME) else idName
        return name + ":" + widthDp + "x" + heightDp + ":" + if (visible) "v" else "h"
    }

    companion object {
        fun of(view: ImageView): MarkCandidate {
            val density = view.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
            return MarkCandidate(
                view = view,
                widthDp = (view.width / density).toInt(),
                heightDp = (view.height / density).toInt(),
                visible = view.visibility == View.VISIBLE,
                idName = idNameOf(view),
            )
        }

        private fun idNameOf(view: View): String {
            if (view.id == View.NO_ID) return "no-id"
            return runCatching { view.resources.getResourceEntryName(view.id) }
                .getOrElse { "id-0x" + Integer.toHexString(view.id) }
        }

        private const val ROUND_IMAGE_VIEW = "COUIRoundImageView"

        /** 16dp is the corner picture (out of range); 32dp is the app icon. */
        private const val MARK_MIN_DP = 24
        private const val MARK_MAX_DP = 48
        private const val MARK_SQUARE_TOLERANCE_DP = 8
        private const val MAX_ID_NAME = 18
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
