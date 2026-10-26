package com.c17.notify.replace.hook.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import com.c17.notify.replace.core.SystemPackages
import com.c17.notify.replace.diagnostics.DiagnosticEvent
import com.c17.notify.replace.diagnostics.DiagnosticLevel
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.diagnostics.OccurrencePolicy
import com.c17.notify.replace.hook.HookRegistrar
import com.c17.notify.replace.hook.icon.HostAvatarComposition
import com.c17.notify.replace.hook.icon.HostAvatarPresence
import com.c17.notify.replace.hook.icon.NotificationIconResolver
import com.c17.notify.replace.hook.memberMissing
import com.c17.notify.replace.hook.runtimeFailure
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

internal class NotificationPanelHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val configuration: SystemUiConfiguration,
    private val members: PanelMembers,
) {
    private val systemUiIdCache = ConcurrentHashMap<String, Int>()
    private val headerIconClaims = HeaderIconClaimRegistry<ImageView, Drawable>()

    /**
     * Neutral `IconInfo` palette (light, dark) for the grouped-row count pill, as produced by the
     * host for monochrome small icons. Filled from the first grayscale row it sees, or derived from
     * `getExpandedPillBgColor()` with a white drawable before that.
     */
    @Volatile
    private var cachedNeutralPillPalette: IntArray? = null

    /**
     * The notification whose row `updateIconRoundness` is currently rounding. That method is the
     * only caller of `getHeaderIconBadgeDrawable`, and it calls it synchronously, so this is what
     * tells the badge hook which of the two forms it is answering for (see
     * [installHeaderBadgeDrawableHook]).
     */
    private val headerBadgeRow = ThreadLocal<StatusBarNotification?>()

    /** `OplusNotificationHeaderViewWrapperExImp.resolveHeaderIconContentSizePx()`, resolved once. */
    @Volatile
    private var contentSizeMethod: Method? = null
    private var contentSizeMethodResolved = false

    /**
     * The box size the header icon is composed into, remembered from the last prefill. The badge
     * hook runs synchronously from the host's own rounding pass and has no wrapper to ask, so this
     * is the only size available when it has to compose an avatar itself.
     */
    @Volatile
    private var lastHeaderContentSizePx = 0

    fun install() {
        installOplusHeaderHooks()
        installHostCompositionHook()
        installGroupIconManagerHooks()
        installPackageIconResolverHooks()
        installPackageIconByNameHooks()
        installHiddenContentHooks()
        members.headerOnContentUpdated?.let { method ->
            hooks.install(method, "systemui.panel.header.onContentUpdated") { chain ->
                val result = chain.proceed()
                chain.instanceOrNull?.let { wrapper ->
                    applyPanelIcon(wrapper, rowCandidate = chain.args.firstOrNull())
                }
                result
            }
        }
        members.headerResolveHeaderViews?.let { method ->
            hooks.install(method, "systemui.panel.header.resolveHeaderViews") { chain ->
                val result = chain.proceed()
                chain.instanceOrNull?.let { wrapper -> applyPanelIcon(wrapper) }
                result
            }
        }
        members.headerSetIsChildInGroup?.let { method ->
            hooks.install(method, "systemui.panel.header.setIsChildInGroup") { chain ->
                val result = chain.proceed()
                // Both directions repaint the header. Leaving the group (false) needs the outline
                // restored; entering the group (true) — the moment a second notification of the
                // same app arrives — hands the row to the host's native small-icon composition, and
                // the previous row kept that raw look forever when no content update followed (the
                // weather "special focus" alert grouped under a newer weather post). Re-apply now
                // and once more after a short delay, because the host can compose asynchronously.
                chain.instanceOrNull?.let { wrapper ->
                    applyPanelIcon(wrapper)
                    if (chain.args.firstOrNull() == true) {
                        runCatching { members.notificationViewWrapperRow.get(wrapper) }.getOrNull()
                            ?.let { row ->
                                val snapshot = configuration.snapshot
                                (row as? View)?.postDelayed(
                                    { if (configuration.isCurrent(snapshot)) applyPanelIcon(wrapper) },
                                    PANEL_RETRY_DELAY_MILLIS,
                                )
                            }
                    }
                }
                result
            }
        }
        members.oplusGroupInitIcon?.let { method ->
            hooks.install(method, "systemui.panel.group.initIcon") { chain ->
                val result = chain.proceed()
                chain.instanceOrNull?.let { wrapper ->
                    applyPanelIcon(wrapper, target = PanelIconTarget.OplusGroupSummary)
                }
                result
            }
        }
        members.oplusGroupResolveHeaderViews?.let { method ->
            hooks.install(method, "systemui.panel.group.resolveHeaderViews") { chain ->
                val result = chain.proceed()
                chain.instanceOrNull?.let { wrapper ->
                    applyPanelIcon(wrapper, target = PanelIconTarget.OplusGroupSummary)
                }
                result
            }
        }
        // ColorOS 17 host (OplusNotificationGroupExtImpl) repaints the collapsed group badge in
        // flushIcon(NotificationChildrenContainer) instead of the removed template wrapper.
        members.oplusGroupFlushIcon?.let { method ->
            hooks.install(method, "systemui.panel.group.flushIcon") { chain ->
                val result = chain.proceed()
                chain.instanceOrNull?.let { wrapper ->
                    applyPanelIcon(wrapper, target = PanelIconTarget.OplusGroupSummary)
                }
                result
            }
        }
        // ColorOS 17 hands the badge icon view over directly in
        // attachCollapsedGroupBadge(NotificationHeaderView, CachingIconView, NotificationChildrenContainer),
        // which is the only group-summary entry that still exposes the icon view and its row.
        members.oplusGroupAttachBadge?.let { method ->
            hooks.install(method, "systemui.panel.group.attachBadge") { chain ->
                val result = chain.proceed()
                val snapshot = configuration.snapshot
                if (!snapshot.config.panelIconReplacementEnabled) return@install result
                val iconView = chain.args.getOrNull(1) as? ImageView
                val container = chain.args.getOrNull(2)
                if (iconView == null || container == null) return@install result
                val row = runCatching {
                    members.childrenContainerContainingNotification?.invoke(container)
                }.getOrNull()
                injectGroupSummaryIcon(iconView, row, snapshot)
                result
            }
        }
    }

    /**
     * With "hide notification content" the row renders its **public** (redacted) layout, where
     * ColorOS never composes a contact avatar — the icon is the app's own small icon. The header
     * path above deliberately yields to the host avatar, so in this state nothing at all was
     * replaced and the lock-screen hero kept the stock icon. These hooks cover exactly that form;
     * they only ever run while `shouldShowPublic()` is true, so the normal avatar composition in
     * the notification shade can never be touched by them.
     */
    private fun installHiddenContentHooks() {
        val setHideSensitive = members.rowSetHideSensitive ?: return
        hooks.install(setHideSensitive, "systemui.panel.row.setHideSensitive") { chain ->
            val result = chain.proceed()
            chain.instanceOrNull?.let { row ->
                // The public layout is (re)inflated right after this call, so run once now and
                // again after the next frames — the icon may not exist on the first pass yet.
                applyHiddenContentIcon(row)
                (row as? View)?.let { view ->
                    val snapshot = configuration.snapshot
                    view.post {
                        if (configuration.isCurrent(snapshot)) applyHiddenContentIcon(row)
                    }
                    view.postDelayed(
                        {
                            if (configuration.isCurrent(snapshot)) applyHiddenContentIcon(row)
                        },
                        PANEL_RETRY_DELAY_MILLIS,
                    )
                }
            }
            result
        }
    }

    /**
     * @return true when the row is showing its public layout and the theme icon was applied there.
     */
    private fun applyHiddenContentIcon(row: Any): Boolean {
        val snapshot = configuration.snapshot
        if (!snapshot.config.panelIconReplacementEnabled) return false
        if (!snapshot.resolver.panelThemeIconsEnabled()) return false
        if (members.rowShouldShowPublic?.invoke(row) as? Boolean != true) return false
        val layout = members.rowGetPublicLayout?.invoke(row) as? View ?: return false
        val sbn = statusBarNotificationFromRow(row) ?: return false
        val packageName = sbn.packageName
        // A collapsed-group row composites its own badge from the app icon and never inflates a
        // public layout that carries one, so the lookup below always failed there and produced a
        // per-package warning that read like a real replacement failure. The group path has its
        // own hooks; keep this one quiet for those rows.
        if (sbn.key.contains("|g:")) return false
        try {
            val iconView = layout.findPublicIcon() ?: run {
                diagnostics.report(
                    level = DiagnosticLevel.Warning,
                    event = DiagnosticEvent.PackageIconResolved,
                    message = "角标诊断：$packageName 隐藏通知内容形态未找到公开视图图标，保留 ColorOS 原图标",
                    attributes = mapOf("package" to packageName),
                    occurrence = OccurrencePolicy.Once("hidden_content_icon_missing:$packageName"),
                )
                return false
            }
            val drawable = snapshot.resolver.resolvePanelThemeDrawable(iconView.context, sbn) ?: return false
            if (!configuration.isCurrent(snapshot)) return false
            iconView.background = null
            iconView.foreground = null
            iconView.imageTintList = null
            iconView.clearColorFilter()
            iconView.setImageDrawable(drawable)
            diagnostics.report(
                level = DiagnosticLevel.Info,
                event = DiagnosticEvent.PackageIconResolved,
                message = "角标诊断：$packageName 隐藏通知内容形态（公开视图）已替换为主题图标",
                attributes = mapOf("package" to packageName),
                occurrence = OccurrencePolicy.Once("hidden_content_replaced:$packageName"),
            )
            return true
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "panel:hidden_content_icon",
                message = "隐藏通知内容形态图标注入失败，保留 ColorOS 原图标",
                cause = exception,
                revision = snapshot.revision,
            )
            return false
        }
    }

    private fun View.findPublicIcon(): ImageView? {
        findViewById<ImageView>(android.R.id.icon)?.let { return it }
        val id = systemUiId("icon")
        return if (id != 0) findViewById(id) else null
    }

    /**
     * Replaces the app icon ColorOS composes itself: the contact-avatar badge ("显示个人头像"), the
     * icon next to an expanded group row and the lock-screen notification icon list.
     *
     * Those rows ask `NotificationResolverExImpl.getPackageIconForNotification(...)` for their icon
     * and never read `Notification#smallIcon`, so neither writing into that field nor patching the
     * status bar descriptor ever reached the badge (the previous attempts failed silently exactly
     * because of this). Handing the desktop theme icon back from the resolver itself is the only
     * entry point that covers every one of those surfaces at once, and it deliberately does not
     * yield to the host avatar: the composition keeps its own contact photo, only the small
     * app-icon badge changes.
     *
     * The helper is a suspend function on ColorOS 15+, i.e. erasing to `(sbn, continuation) →
     * Object`. Returning the Drawable directly instead of proceeding is what makes it work there:
     * the caller's continuation either receives it right away or reads it as the suspension
     * result, while a host that does suspend internally would leak nothing because its own
     * continuation is simply dropped.
     */
    private fun installPackageIconResolverHooks() {
        val resolver = members.packageIconResolver ?: return
        hooks.install(
            resolver.getPackageIconForNotification,
            "systemui.panel.packageIconResolver",
        ) { chain ->
            val snapshot = configuration.snapshot
            if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
            if (!snapshot.resolver.panelThemeIconsEnabled()) return@install chain.proceed()
            val sbn = chain.args.firstOrNull { it is StatusBarNotification } as? StatusBarNotification
                ?: return@install chain.proceed()
            val context = resolverContext(resolver, chain.instanceOrNull) ?: return@install chain.proceed()
            val packageName = sbn.packageName
            val replacement = try {
                snapshot.resolver.resolvePanelThemeDrawable(context, sbn)
            } catch (throwable: Throwable) {
                diagnostics.runtimeFailure(
                    scope = "panel:package_icon_resolver",
                    message = "角标诊断：$packageName 宿主应用图标读取失败，保留 ColorOS 原图标",
                    cause = throwable,
                    revision = snapshot.revision,
                )
                null
            }
            if (replacement == null) {
                diagnostics.report(
                    level = DiagnosticLevel.Warning,
                    event = DiagnosticEvent.PackageIconResolved,
                    message = "角标诊断：$packageName 主题图标解析失败，角标保持原版",
                    attributes = mapOf("package" to packageName),
                    occurrence = OccurrencePolicy.Once("package_icon_missing:$packageName"),
                )
                return@install chain.proceed()
            }
            val suspendSignature =
                resolver.getPackageIconForNotification.returnType == java.lang.Object::class.java
            diagnostics.report(
                level = DiagnosticLevel.Info,
                event = DiagnosticEvent.PackageIconResolved,
                message = "角标诊断：$packageName 已替换为主题图标" +
                    "（返回值=${replacement.javaClass.simpleName}，suspend=$suspendSignature）",
                attributes = mapOf("package" to packageName),
                occurrence = OccurrencePolicy.Once("package_icon_replaced:$packageName"),
            )
            replacement
        }
    }

    /**
     * ColorOS composes "contact photo + app-icon badge" in exactly two places, and both go through
     * the same helper: `LayerDrawableWithAppIconKt.createBackgroundWithAppIcon(context, origin,
     * badge, packageName, contentSizePx)` — called from `getIconWithBadge` and from
     * `scheduleApplyIconWithBadge`.
     *
     * Swapping the **badge** argument for the configured theme icon is therefore the whole fix for
     * "the row shows the theme icon instead of the avatar". The host draws, rounds, caches and
     * re-applies a picture that was correct when it was made; the module never has to replace a
     * finished composition, and the row keeps the stock layout — a contact photo with a small
     * corner badge. When the photo has not arrived yet the same call produces "app icon + themed
     * badge", which is also what the stock build shows at that moment.
     */
    private fun installHostCompositionHook() {
        val context = systemUiContext() ?: run {
            diagnostics.memberMissing(
                scope = "panel:compose_with_app_icon",
                message = "取不到 SystemUI Context，头像+角标合成钩子未安装",
            )
            return
        }
        val method = HostAvatarComposition.method(context) ?: run {
            diagnostics.memberMissing(
                scope = "panel:compose_with_app_icon",
                message = "未找到 createBackgroundWithAppIcon(...)，头像+角标合成钩子未安装",
            )
            return
        }
        hooks.install(method, "systemui.panel.composeWithAppIcon") { chain ->
            val snapshot = configuration.snapshot
            if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
            if (!snapshot.resolver.panelThemeIconsEnabled()) return@install chain.proceed()
            // (Context, origin: Drawable, badge: Drawable, packageName: String, contentSizePx: Int)
            //
            // Located by type instead of by index: the helper is Kotlin top-level code compiled
            // against `Drawable`, so a null badge, a reordered signature or an extra parameter on
            // another ColorOS build would silently make the old index-based lookup a no-op — which
            // is exactly how this hook ended up installed but never firing.
            val args = chain.args
            val hostContext = args.firstOrNull { it is Context } as? Context
            val packageName = args.firstOrNull { it is String } as? String
            if (hostContext == null || packageName.isNullOrEmpty()) {
                diagnostics.report(
                    level = DiagnosticLevel.Debug,
                    event = DiagnosticEvent.PackageIconResolved,
                    message = "角标诊断：合成钩子参数里没有 Context/包名，保持宿主角标",
                    attributes = mapOf(
                        "args" to args.joinToString(prefix = "[", postfix = "]") {
                            it?.javaClass?.simpleName ?: "null"
                        },
                    ),
                    occurrence = OccurrencePolicy.Once("compose_badge_args"),
                )
                return@install chain.proceed()
            }
            val themed = snapshot.resolver.resolveThemeDrawableByPackage(hostContext, packageName)
            if (themed == null) {
                diagnostics.report(
                    level = DiagnosticLevel.Debug,
                    event = DiagnosticEvent.PackageIconResolved,
                    message = "角标诊断：$packageName 没有可用的主题图标，保持宿主角标",
                    attributes = mapOf("package" to packageName),
                    occurrence = OccurrencePolicy.Once("compose_badge_missing:$packageName"),
                )
                return@install chain.proceed()
            }
            // The avatar is the first Drawable argument, the corner badge the second.
            val badgeIndex = args.withIndex()
                .filter { it.value is Drawable }
                .map { it.index }
                .getOrNull(1)
            if (badgeIndex == null) {
                diagnostics.report(
                    level = DiagnosticLevel.Debug,
                    event = DiagnosticEvent.PackageIconResolved,
                    message = "角标诊断：$packageName 宿主合成调用没有角标位，保持原样",
                    attributes = mapOf(
                        "package" to packageName,
                        "args" to args.joinToString(prefix = "[", postfix = "]") {
                            it?.javaClass?.simpleName ?: "null"
                        },
                    ),
                    occurrence = OccurrencePolicy.Once("compose_badge_nobadge:$packageName"),
                )
                return@install chain.proceed()
            }
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.PackageIconResolved,
                message = "角标诊断：$packageName 宿主合成头像时角标用主题图标",
                attributes = mapOf(
                    "package" to packageName,
                    "badge" to badgeIndex.toString(),
                ),
                occurrence = OccurrencePolicy.Once("compose_badge:$packageName"),
            )
            chain.proceed(args.toTypedArray().also { it[badgeIndex] = themed })
        }
        diagnostics.report(
            level = DiagnosticLevel.Info,
            event = DiagnosticEvent.HookInstalled,
            message = "角标诊断：头像+角标合成钩子已安装（角标将换成主题图标）",
            attributes = mapOf("method" to method.name),
            occurrence = OccurrencePolicy.Once("compose_badge_installed"),
        )
    }

    /**
     * With "hide notification content" on, the lock-screen hero asks for the app icon **by package**
     * (`getPackageIcon`) instead of through the notification-scoped entry above. That is why the
     * capsule at the bottom was already themed while the notification above it still showed the
     * stock icon: only the notification-scoped path was covered.
     *
     * `getHeaderIconBadgeDrawable` is deliberately *not* part of this pair — see
     * [installHeaderBadgeDrawableHook].
     */
    private fun installPackageIconByNameHooks() {
        val resolver = members.packageIconResolver ?: return
        installPackageIconByNameHook(
            resolver = resolver,
            method = resolver.getPackageIcon,
            tag = "systemui.panel.packageIconByName",
        )
        resolver.getHeaderIconBadgeDrawable?.let { method ->
            installHeaderBadgeDrawableHook(resolver, method)
        }
    }

    private fun installPackageIconByNameHook(
        resolver: PackageIconResolverMembers,
        method: Method?,
        tag: String,
    ) {
        val target = method ?: return
        hooks.install(target, tag) { chain ->
            val snapshot = configuration.snapshot
            if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
            if (!snapshot.resolver.panelThemeIconsEnabled()) return@install chain.proceed()
            val rawArgument = chain.args.firstOrNull { it is String } as? String
                ?: return@install chain.proceed()
            val packageName = packageNameFromArgument(rawArgument)
            val context = resolverContext(resolver, chain.instanceOrNull) ?: return@install chain.proceed()
            val replacement = snapshot.resolver.resolveThemeDrawableByPackage(context, packageName)
            if (replacement == null) {
                diagnostics.report(
                    level = DiagnosticLevel.Warning,
                    event = DiagnosticEvent.PackageIconResolved,
                    message = "角标诊断：$packageName 按包名解析主题图标失败，保留 ColorOS 原图标",
                    attributes = mapOf("package" to packageName),
                    occurrence = OccurrencePolicy.Once("package_icon_by_name_missing:$packageName"),
                )
                return@install chain.proceed()
            }
            diagnostics.report(
                level = DiagnosticLevel.Info,
                event = DiagnosticEvent.PackageIconResolved,
                message = "角标诊断：$packageName 已按包名替换为主题图标（$tag）",
                attributes = mapOf("package" to packageName),
                occurrence = OccurrencePolicy.Once("package_icon_by_name_replaced:$packageName"),
            )
            replacement
        }
    }

    /**
     * `getHeaderIconBadgeDrawable(key)` is asked **only** by
     * `OplusNotificationHeaderViewWrapperExImp.updateIconRoundness(boolean)`, and it is a pure
     * `headerIconBadgeCache.get(key)` lookup (decompiled) — the composition itself runs in a
     * coroutine (`scheduleApplyIconWithBadge` → `createBackgroundWithAppIcon`) whose result is
     * applied through `applyExpandedCanvasIconWithBadge`, which *writes it back into the same
     * cache*. The host only evicts on row dismissal (`setRemoved()`), never on notification
     * updates. That protocol drives everything below:
     *
     * - **null is a signal, not a failure**: it tells `updateIconRoundness` "nothing composed
     *   yet", which is exactly what makes the host schedule its async "avatar + badge"
     *   composition. A non-null substitute (the 4.0.1–4.0.4 theme icon) is treated as a finished
     *   composition — applied, re-cached, and the avatar composition never runs. That is how the
     *   first WeChat message after a reboot got pinned to the theme icon;
     * - a notification that carries a contact avatar must therefore be handed back untouched,
     *   with stale cache entries (placeholder-era composition, or a theme icon pinned here
     *   earlier) evicted so the host recomposes from the notification it has *now*;
     * - a collapsed group summary or a placeholder-only row has no avatar to lose, so the
     *   configured theme icon replaces the "app icon + badge" the host would compose (3.5.8,
     *   3.5.9). If the app attaches the photo later and re-posts, the renderable branch detects
     *   the new avatar slot and evicts the pinned theme icon, and the row turns into
     *   "avatar + badge" like every later message.
     *
     * The row being rounded is recorded by [installOplusHeaderRoundnessReapply] right before the
     * host calls in, which is what makes the two cases distinguishable.
     */
    private fun installHeaderBadgeDrawableHook(
        resolver: PackageIconResolverMembers,
        method: Method,
    ) {
        hooks.install(method, "systemui.panel.headerBadgeDrawable") { chain ->
            val snapshot = configuration.snapshot
            if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
            if (!snapshot.resolver.panelThemeIconsEnabled()) return@install chain.proceed()
            val rowSbn = headerBadgeRow.get()
            val rowContext = resolverContext(resolver, chain.instanceOrNull)
            val rawArgument = chain.args.firstOrNull { it is String } as? String
            val packageName = rowSbn?.packageName
                ?: rawArgument?.let(::packageNameFromArgument)
                ?: return@install chain.proceed()
            val context = rowContext ?: return@install chain.proceed()

            if (rowSbn != null) {
                val verdict = HostAvatarPresence.evaluate(context, rowSbn)
                if (verdict.renderable) {
                    // The notification really carries a sender photo: the "avatar + badge"
                    // composition must be left to the host. `getHeaderIconBadgeDrawable` is a
                    // pure LruCache lookup (decompiled: `headerIconBadgeCache.get(key)`), so a
                    // null return means "not composed yet" — that is the very signal which makes
                    // the host schedule its async avatar composition and cache the result via
                    // `putHeaderIconBadgeDrawable`. Handing back any non-null substitute (the
                    // 4.0.1–4.0.4 theme icon) makes the host treat it as a finished composition,
                    // apply *and re-cache* it, and the avatar composition never runs — the first
                    // WeChat message after a reboot was pinned to the theme icon exactly this way.
                    // So never substitute here; only evict stale cache entries so the host
                    // recomposes from the notification it has now:
                    //  1) a placeholder-era composition (host composed "app icon + badge" before
                    //     the app attached the photo);
                    //  2) a theme icon an earlier pass of this hook pinned into the cache.
                    val themedHere = themedBadgeRows.get(rowSbn.key) != null
                    val hosted = chain.proceed()
                    val hostedDrawable = hosted as? Drawable
                    val stalePlaceholder = hostedDrawable != null &&
                        HostAvatarPresence.looksLikeLauncherIcon(context, packageName, hostedDrawable)
                    if (themedHere || stalePlaceholder) {
                        evictHeaderIconBadge(resolver, chain.instanceOrNull, rawArgument)
                        themedBadgeRows.remove(rowSbn.key)
                        diagnostics.report(
                            level = DiagnosticLevel.Info,
                            event = DiagnosticEvent.HostAvatarYield,
                            message = "角标诊断：$packageName 通知已有头像，清除过期角标缓存，由宿主重新合成头像+角标",
                            attributes = mapOf(
                                "package" to packageName,
                                "key" to rowSbn.key,
                                "verdict" to verdict.reason,
                                "stale" to if (stalePlaceholder) "host-placeholder" else "module-themed",
                            ),
                            occurrence = OccurrencePolicy.Once("header_badge_evict:${rowSbn.key}"),
                        )
                        return@install null
                    }
                    if (hostedDrawable == null) {
                        // The cache is still empty, i.e. the host has launched its composition
                        // coroutine but has not finished it yet. Filling that window with the very
                        // same picture removes the "原生图标一闪而过" the user sees on the first
                        // frame; the host's own result lands a frame or two later and replaces it
                        // with an identical drawable.
                        val prefillSize = lastHeaderContentSizePx.takeIf { it > 0 }
                            ?: snapshot.resolver.resolveThemeDrawableByPackage(context, packageName)
                                ?.intrinsicWidth
                                ?.takeIf { it > 0 }
                        val prefilled = prefillSize?.let { size ->
                            snapshot.resolver.composeHostAvatarPreview(
                                context = context,
                                sbn = rowSbn,
                                contentSizePx = size,
                                allowGroupSummaryAvatar = HostAvatarPresence.isGroupSummary(rowSbn),
                            )
                        }
                        if (prefilled != null) {
                            diagnostics.report(
                                level = DiagnosticLevel.Debug,
                                event = DiagnosticEvent.HostAvatarPrefill,
                                message = "角标缓存尚未合成，模块先行铺上头像+角标",
                                attributes = mapOf(
                                    "package" to packageName,
                                    "key" to rowSbn.key,
                                    "verdict" to verdict.reason,
                                    "size" to (prefillSize ?: 0).toString(),
                                ),
                                occurrence = OccurrencePolicy.Once("header_badge_prefill:${rowSbn.key}"),
                            )
                            return@install prefilled
                        }
                    }
                    diagnostics.report(
                        level = DiagnosticLevel.Debug,
                        event = DiagnosticEvent.HostAvatarYield,
                        message = "角标诊断：$packageName 已有头像，交回宿主合成结果",
                        attributes = mapOf(
                            "package" to packageName,
                            "key" to rowSbn.key,
                            "verdict" to verdict.reason,
                            "hosted" to (hostedDrawable?.javaClass?.simpleName ?: "null"),
                        ),
                        occurrence = OccurrencePolicy.Once("header_badge_hosted:${rowSbn.key}"),
                    )
                    return@install hosted
                }
                // An aggregated row ("[2条] …") is flagged as a group summary, so the renderable
                // branch above never takes it — and the host has no avatar composition for a
                // summary either, it paints the plain app icon. When the headline account behind
                // the aggregate still ships its own picture in the large icon, compose it here:
                // otherwise the row keeps the app icon while every single-chat row beside it shows
                // "avatar + badge".
                if (HostAvatarPresence.isGroupSummary(rowSbn)) {
                    val summaryVerdict = HostAvatarPresence.evaluate(
                        context = context,
                        sbn = rowSbn,
                        allowGroupSummaryAvatar = true,
                    )
                    if (summaryVerdict.renderable) {
                        val hosted = chain.proceed() as? Drawable
                        val size = hosted?.intrinsicWidth?.takeIf { it > 0 }
                            ?: snapshot.resolver.resolveThemeDrawableByPackage(context, packageName)
                                ?.intrinsicWidth
                                ?.takeIf { it > 0 }
                            ?: 0
                        val composed = if (size > 0) {
                            snapshot.resolver.composeHostAvatarPreview(
                                context = context,
                                sbn = rowSbn,
                                contentSizePx = size,
                                allowGroupSummaryAvatar = true,
                            )
                        } else {
                            null
                        }
                        if (composed != null) {
                            diagnostics.report(
                                level = DiagnosticLevel.Info,
                                event = DiagnosticEvent.PackageIconResolved,
                                message = "角标诊断：$packageName 聚合摘要带真头像，按头像+角标合成",
                                attributes = mapOf(
                                    "package" to packageName,
                                    "key" to rowSbn.key,
                                    "verdict" to summaryVerdict.reason,
                                    "difference" to summaryVerdict.difference.toString(),
                                    "size" to size.toString(),
                                ),
                                occurrence = OccurrencePolicy.Once("header_badge_summary_avatar:${rowSbn.key}"),
                            )
                            return@install composed
                        }
                        diagnostics.report(
                            level = DiagnosticLevel.Debug,
                            event = DiagnosticEvent.PackageIconResolved,
                            message = "角标诊断：$packageName 聚合摘要判定为有头像但合成失败，交给宿主",
                            attributes = mapOf(
                                "package" to packageName,
                                "key" to rowSbn.key,
                                "size" to size.toString(),
                            ),
                            occurrence = OccurrencePolicy.Once("header_badge_summary_failed:${rowSbn.key}"),
                        )
                        // The row *has* a photo, only this module cannot compose it (not an
                        // avatar-priority package, undecodable artwork, ...). Falling through to the
                        // theme icon would paint over the avatar the host is about to compose — the
                        // "theme icon ↔ avatar+角标" flip. Hand the row back instead.
                        return@install chain.proceed()
                    } else {
                        diagnostics.report(
                            level = DiagnosticLevel.Debug,
                            event = DiagnosticEvent.PackageIconResolved,
                            message = "角标诊断：$packageName 聚合摘要没有头像位，按主题图标替换",
                            attributes = mapOf(
                                "package" to packageName,
                                "key" to rowSbn.key,
                                "verdict" to summaryVerdict.reason,
                                "difference" to summaryVerdict.difference.toString(),
                            ),
                            occurrence = OccurrencePolicy.Once("header_badge_summary_plain:${rowSbn.key}"),
                        )
                    }
                }
                // A row whose notification carries no large icon never gets a host-composed photo
                // (every logged contact-photo verdict had a largeIcon; the rows without one had
                // the host paint the bare app icon), so it belongs to the replacement branch
                // below and reaches the theme icon on the very first bind. When the sender photo
                // exists but loads late, WeChat re-posts the notification and the renderable
                // branch above takes over from that re-bind on.
                if (verdict.reason != HostAvatarPresence.REASON_GROUP_SUMMARY) {
                    diagnostics.report(
                        level = DiagnosticLevel.Debug,
                        event = DiagnosticEvent.HostAvatarYield,
                        message = "角标诊断：$packageName 暂无头像（${verdict.reason}），保持原生图标+主题角标",
                        attributes = mapOf(
                            "package" to packageName,
                            "key" to rowSbn.key,
                            "verdict" to verdict.reason,
                        ),
                        occurrence = OccurrencePolicy.Once("header_badge_native:${rowSbn.key}"),
                    )
                    return@install chain.proceed()
                }
                // The avatar slot holds the app's own icon (or the row has no avatar at all, e.g.
                // a group summary or an ongoing "视频通话中"): the host would compose "app icon +
                // badge", so the configured theme icon replaces it. The host caches the theme
                // icon via `putHeaderIconBadgeDrawable`; when the app later attaches the photo and
                // re-posts, the renderable branch above detects it and evicts that cache entry,
                // letting the host recompose "avatar + badge" for the same notification.
                val replacement = snapshot.resolver.resolveThemeDrawableByPackage(context, packageName)
                if (replacement != null) {
                    themedBadgeRows.put(rowSbn.key, true)
                    diagnostics.report(
                        level = DiagnosticLevel.Info,
                        event = DiagnosticEvent.HostAvatarReplace,
                        message = "角标诊断：$packageName 头像位是应用图标占位（${verdict.reason}），按主题图标替换",
                        attributes = mapOf(
                            "package" to packageName,
                            "key" to rowSbn.key,
                            "verdict" to verdict.reason,
                            "difference" to verdict.difference.toString(),
                        ),
                        occurrence = OccurrencePolicy.Once("header_badge_placeholder:${rowSbn.key}"),
                    )
                    return@install replacement
                }
                return@install chain.proceed()
            }

            val replacement = snapshot.resolver.resolveThemeDrawableByPackage(context, packageName)
                ?: return@install chain.proceed()
            diagnostics.report(
                level = DiagnosticLevel.Info,
                event = DiagnosticEvent.PackageIconResolved,
                message = "角标诊断：$packageName 无头像行（聚合摘要）已替换为主题图标",
                attributes = mapOf("package" to packageName, "key" to (rowSbn?.key ?: rawArgument)),
                occurrence = OccurrencePolicy.Once("header_badge_replaced:$packageName"),
            )
            replacement
        }
    }

    /**
     * Notification keys whose badge cache entry this hook pinned to the theme icon. The entry is
     * consulted on the avatar-bearing passes above: once the app attaches the sender photo and
     * re-posts, the cached theme icon is stale and gets evicted so the host recomposes.
     */
    private val themedBadgeRows = LruCache<String, Boolean>(64)

    /**
     * `NotificationResolverExImpl.removeHeaderIconBadgeDrawable(String)` — the host only calls it
     * itself from `setRemoved()` (row dismissed), never on notification updates, so stale badge
     * compositions live as long as the notification does unless something evicts them.
     */
    private fun evictHeaderIconBadge(
        resolver: PackageIconResolverMembers,
        host: Any?,
        cacheKey: String?,
    ) {
        val method = resolver.removeHeaderIconBadgeDrawable ?: return
        if (host == null || cacheKey == null) return
        runCatching { method.invoke(host, cacheKey) }
    }

    /**
     * `getHeaderIconBadgeDrawable` takes the notification key, not a package name:
     * `0|com.android.mms|0|com.android.mms:sms::384|10119|com.android.mms`. Feeding that whole
     * string to the theme resolver always failed, so the host composed the collapsed-group badge
     * out of the raw small icon (SMS: a white glyph) and the row showed a blank block. The key's
     * second segment is the package name; anything without separators is already a package.
     */
    private fun packageNameFromArgument(raw: String): String {
        val value = raw.trim()
        val parts = value.split('|')
        if (parts.size >= 2 && parts[1].contains('.')) return parts[1]
        return value
    }

    /**
     * The resolver owns the SystemUI context, so asking the hooked instance is both cheaper and
     * safer than poking at ActivityThread from a hook running before the host finished booting.
     */
    private fun resolverContext(
        resolver: PackageIconResolverMembers,
        host: Any?,
    ): Context? {
        if (host != null) {
            runCatching { resolver.getContext?.invoke(host) as? Context }
                .getOrNull()
                ?.let { return it }
        }
        return systemUiContext()
    }

    private fun systemUiContext(): Context? = try {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }

    private fun injectGroupSummaryIcon(
        iconView: ImageView,
        row: Any?,
        snapshot: RuntimeSnapshot,
        verifyAfterWrite: Boolean = true,
    ) {
        try {
            val sbn = row?.let(::statusBarNotificationFromRow) ?: return
            val plan = snapshot.resolver.resolveGroupSummaryIconPlan(
                context = iconView.context,
                sbn = sbn,
                originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
            ) ?: return
            if (!configuration.isCurrent(snapshot)) return
            writeGroupSummaryPlan(iconView, plan)
            reportGroupSummaryAnchor(iconView, row, sbn)
            writeAlternateGroupBadge(iconView, row, sbn, snapshot, plan)
            if (verifyAfterWrite) {
                // The guard re-writes the *resolved* plan, never a fresh lookup: the row's verdict
                // can flip while the row lives (the host attaches the child avatar and the plan
                // resolver then answers null), and a guard that re-resolved would silently write
                // nothing and leave the host's own artwork on screen for good — the "aggregate row
                // keeps the raw app icon badge" report.
                keepGroupSummaryIcon(iconView, row, sbn, snapshot) {
                    writeGroupSummaryPlan(iconView, plan)
                }
                verifyGroupSummaryIconAfterWrite(iconView, row, sbn, snapshot, plan = plan)
            }
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "panel:group_attach_badge",
                message = "聚合摘要徽标图标注入失败，保留 ColorOS 原结果",
                cause = exception,
                revision = snapshot.revision,
            )
        }
    }

    /** Writes a resolved group-summary plan into the badge view, stripping the host's decoration. */
    private fun writeGroupSummaryPlan(
        iconView: ImageView,
        plan: NotificationIconResolver.PanelIconRenderPlan,
    ) {
        iconView.clearColorFilter()
        iconView.imageTintList = null
        iconView.setImageDrawable(plan.drawable)
        plan.tintColor?.let { tint ->
            iconView.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
    }

    /**
     * Records which view the collapsed-group write actually landed on.
     *
     * ColorOS hands `attachCollapsedGroupBadge` a `CachingIconView`, but the badge the collapsed row
     * paints is the one inside `oplus_notification_collapsed_group_header > icon_container > icon`.
     * When those are two different instances the module keeps a perfectly themed drawable in a view
     * nobody draws, the takeover guard reports "stable" because that view never changes again, and
     * the row keeps the raw app icon on screen — which is the "two notifications from the same app,
     * one replaced and one original" report. This line is what separates that from a host overwrite.
     */
    @SuppressLint("DiscouragedApi") // The ids belong to the hooked SystemUI APK, not this module.
    private fun reportGroupSummaryAnchor(
        iconView: ImageView,
        row: Any?,
        sbn: StatusBarNotification,
    ) {
        val rowView = row as? View ?: return
        val idName = runCatching {
            if (iconView.id == View.NO_ID) "no_id" else iconView.resources.getResourceEntryName(iconView.id)
        }.getOrDefault("unknown")
        val badge = rowView.findOplusGroupSummaryIcon()
        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.PackageIconResolved,
            message = "聚合徽标定位：${sbn.packageName} 写入 id=$idName " +
                "size=${iconView.width}x${iconView.height} attached=${iconView.isAttachedToWindow} " +
                "visible=${iconView.visibility == View.VISIBLE} 徽标命中=${badge != null} " +
                "同实例=${badge === iconView}",
            attributes = mapOf("package" to sbn.packageName, "key" to sbn.key),
            occurrence = OccurrencePolicy.Once("panel:group_anchor:${sbn.key}"),
        )
    }

    /**
     * Writes the same plan into the badge the collapsed row actually paints, when that badge is not
     * the view the host handed over. A no-op on hosts where both are the same instance.
     */
    private fun writeAlternateGroupBadge(
        written: ImageView,
        row: Any?,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
        plan: NotificationIconResolver.PanelIconRenderPlan,
    ) {
        val rowView = row as? View ?: return
        val badge = rowView.findOplusGroupSummaryIcon() ?: return
        if (badge === written) return
        writeGroupSummaryPlan(badge, plan)
        keepGroupSummaryIcon(badge, row, sbn, snapshot) { writeGroupSummaryPlan(badge, plan) }
    }

    /** Same race as [verifyIconAfterWrite], for the badge handed over by `attachCollapsedGroupBadge`. */
    private fun verifyGroupSummaryIconAfterWrite(
        iconView: ImageView,
        row: Any,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
        pass: Int = 0,
        plan: NotificationIconResolver.PanelIconRenderPlan? = null,
    ) {
        if (pass >= MAX_ICON_VERIFY_PASSES) return
        val written = iconView.drawable ?: return
        iconView.postDelayed({
            try {
                if (!configuration.isCurrent(snapshot) || !iconView.isAttachedToWindow) return@postDelayed
                if (statusBarNotificationFromRow(row)?.key != sbn.key) return@postDelayed
                if (iconView.drawable === written) {
                    verifyGroupSummaryIconAfterWrite(iconView, row, sbn, snapshot, pass + 1, plan)
                    return@postDelayed
                }
                diagnostics.report(
                    level = DiagnosticLevel.Debug,
                    event = DiagnosticEvent.IconReapplied,
                    message = "宿主协程重绘了聚合摘要图标，重铺一次",
                    attributes = mapOf("package" to sbn.packageName, "key" to sbn.key),
                    occurrence = OccurrencePolicy.RateLimited("panel:group_icon_reapply", 60_000L),
                )
                if (plan != null) {
                    // Re-write the plan resolved at bind time. Re-resolving here reintroduces the
                    // bug the takeover guard exists for: the row's verdict can flip while it lives,
                    // the resolver then answers null, the rewrite silently does nothing — and with
                    // verifyAfterWrite short-circuited nothing re-arms the guard chain afterwards.
                    writeGroupSummaryPlan(iconView, plan)
                } else {
                    injectGroupSummaryIcon(iconView, row, snapshot, verifyAfterWrite = false)
                }
                verifyGroupSummaryIconAfterWrite(iconView, row, sbn, snapshot, pass + 1, plan)
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "panel:group_icon_reapply",
                    message = "重铺聚合摘要图标失败",
                    cause = exception,
                    revision = snapshot.revision,
                )
            }
        }, PANEL_RETRY_DELAY_MILLIS shl pass)
    }

    /**
     * ColorOS collapsed-group UI extracts WallpaperColors from the app/small icon and paints a
     * solid capsule ("色块"). When we already have a rule/theme replacement we take over the icon
     * only — the count pill stays on the host palette, otherwise rows split into two different
     * badge colours depending on which icon path they took.
     */
    private fun installGroupIconManagerHooks() {
        val groupColorAnchor = members.groupIconInitIconViewColor
        if (groupColorAnchor != null) {
            hooks.install(groupColorAnchor, "systemui.panel.group.initIconViewColor") { chain ->
                val snapshot = configuration.snapshot
                if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
                val entry = chain.args.getOrNull(2) ?: return@install chain.proceed()
                val iconView = chain.args.getOrNull(1) as? ImageView
                    ?: return@install chain.proceed()
                try {
                    val sbn = members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification
                        ?: return@install chain.proceed()
                    val plan = snapshot.resolver.resolveGroupSummaryIconPlan(
                        context = iconView.context,
                        sbn = sbn,
                        originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
                    ) ?: return@install chain.proceed()
                    if (!configuration.isCurrent(snapshot)) return@install chain.proceed()

                    iconView.clearColorFilter()
                    iconView.imageTintList = null
                    iconView.setImageDrawable(plan.drawable)
                    plan.tintColor?.let { tint ->
                        iconView.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                    }
                    null
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:group_icon_color",
                        message = "折叠分组图标着色覆盖失败，交回 ColorOS 原实现",
                        cause = exception,
                        revision = snapshot.revision,
                    )
                    chain.proceed()
                }
            }
        } else {
            // ColorOS 17 removed initIconViewColor(): it was folded into
            // initEntryIconDrawable(). That method also fills the pill and the count, so it can
            // never be swallowed — colour the group icon right after the host returns instead.
            members.groupIconInitEntryIconDrawable?.let { method ->
                hooks.install(method, "systemui.panel.group.initEntryIconDrawable") { chain ->
                    // args[4] == true marks the capsule path, owned by LockScreenCapsuleHooks.
                    if (chain.args.getOrNull(4) as? Boolean == true) return@install chain.proceed()
                    val snapshot = configuration.snapshot
                    if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
                    val entry = chain.args.getOrNull(0) ?: return@install chain.proceed()
                    val iconView = chain.args.getOrNull(1) as? ImageView
                        ?: return@install chain.proceed()
                    // The host paints a round background onto the icon view from this callback, so
                    // wrap it and re-apply our plan once the host's own painting has run.
                    val originalCallback = chain.args.getOrNull(5)
                    val result = if (originalCallback == null) {
                        chain.proceed()
                    } else {
                        // Chain.getArgs() is immutable; hand the wrapped callback over through
                        // proceed(). wrapHostCallback proxies the host's own Function1 class,
                        // which the module cannot implement — SystemUI ships its own stdlib.
                        chain.proceed(
                            chain.args.toTypedArray().also {
                                it[5] = wrapHostCallback(method.parameterTypes[5], originalCallback) {
                                    applyGroupSummaryIcon(iconView, entry, snapshot)
                                }
                            },
                        )
                    }
                    applyGroupSummaryIcon(iconView, entry, snapshot)
                    result
                }
            }
        }

        // The count pill ("上标") is colour-matched to the notification's own small icon by the
        // host: `GroupIconManager$createSmallIconColorTask$2` runs
        // `WallpaperColors.fromDrawable(smallIcon)` → Material3 content surface. Mono small icons
        // therefore land on the neutral surface pill, while a colourful one keeps its hue and the
        // row shows a coloured badge next to neutral neighbours ("颜色不一样"). Nothing here is
        // about our replacement icon — the host reads `Notification.smallIcon`, not the view.
        installGroupPillPaletteAlignment()
    }

    /**
     * Pins every grouped row to the neutral pill palette the host itself produces for monochrome
     * small icons.
     *
     * The neutral pair is taken from the host, never guessed: first from any grayscale row that has
     * already been painted (`IconInfo.isGraysIcon == true`, i.e. the very rows the neighbours use),
     * and otherwise from `GroupIconManager.getExpandedPillBgColor()` fed a plain white drawable —
     * the same function the host runs on a mono small icon. The recolour is done by handing the host
     * a neutral `IconInfo`, so night mode, blur mode, digit colour, alpha and the pill shadow all
     * stay its own business.
     */
    private fun installGroupPillPaletteAlignment() {
        val method = members.groupIconInitPillBgAndNumberColor ?: return
        val pill = members.groupPill ?: return
        hooks.install(method, "systemui.panel.group.pillPalette") { chain ->
            val snapshot = configuration.snapshot
            if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
            val iconInfo = chain.args.getOrNull(1) ?: return@install chain.proceed()
            try {
                val grayscale = pill.isGrayscaleIconInfo.invoke(iconInfo) as? Boolean
                    ?: return@install chain.proceed()
                if (grayscale) {
                    // Remember the palette the host just used for a mono small icon. This is the
                    // reference every coloured row should end up on.
                    val light = pill.iconInfoLightColor.invoke(iconInfo) as? Int
                    val dark = pill.iconInfoDarkColor.invoke(iconInfo) as? Int
                    if (light != null && dark != null) {
                        cachedNeutralPillPalette = intArrayOf(light, dark)
                    }
                    return@install chain.proceed()
                }
                val context = (chain.args.getOrNull(0) as? View)?.context
                    ?: return@install chain.proceed()
                val neutral = neutralPillPalette(context) ?: return@install chain.proceed()
                val replacement = pill.iconInfoConstructor.newInstance(
                    neutral[0],
                    neutral[1],
                    neutral[0],
                    java.lang.Boolean.FALSE,
                )
                chain.proceed(chain.args.toTypedArray().also { it[1] = replacement })
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "panel:group_pill_palette",
                    message = "折叠分组上标配色对齐失败，交回 ColorOS 原实现",
                    cause = exception,
                    revision = snapshot.revision,
                )
                chain.proceed()
            }
        }
    }

    private fun neutralPillPalette(context: Context): IntArray? {
        cachedNeutralPillPalette?.let { return it }
        val palette = members.groupPill ?: return null
        return try {
            val triple = palette.expandedPillBgColor.invoke(
                palette.expandedPillBgColorReceiver,
                context,
                ColorDrawable(Color.WHITE),
            ) ?: return null
            val getters = triple.javaClass.methods
            val light = getters.firstOrNull { it.name == "getFirst" }?.invoke(triple) as? Int
                ?: return null
            val dark = getters.firstOrNull { it.name == "getSecond" }?.invoke(triple) as? Int
                ?: return null
            intArrayOf(light, dark).also { cachedNeutralPillPalette = it }
        } catch (exception: Exception) {
            // A missing Companion helper only costs the cold-start case; coloured rows then keep
            // the host colour until a mono row has been painted once.
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.MemberMissing,
                message = "无法按白色取色推导中性角标配色",
                cause = exception,
                attributes = emptyMap(),
                occurrence = OccurrencePolicy.Once("panel:neutral_pill_palette"),
            )
            null
        }
    }

    private fun installOplusHeaderHooks() {
        val oplusHeader = members.oplusHeader ?: return
        installOplusHeaderColorGuard(oplusHeader)
        installOplusHeaderRoundnessReapply(oplusHeader)
        installOplusHeaderContentUpdatedReapply(oplusHeader)
        installOplusHeaderBadgeApplyReapply(oplusHeader)
    }

    /**
     * `applyExpandedCanvasIconWithBadge(...)` is where every asynchronous host repaint of a row icon
     * ends up: the `Dispatchers.Default` avatar composition (`scheduleApplyIconWithBadge$1`), the
     * rounded/badged redraw after `getHeaderIconBadgeDrawable`. A one-shot check a few hundred
     * milliseconds after our own write loses that race whenever the host is slower, and the row then
     * stays on the plain `smallIcon` for good — the "icon not replaced" rows in the shade.
     *
     * Re-applying right after the host returns wins the race by construction: this is its last write,
     * and the re-apply resolves the same plan the rest of the panel uses (avatar rows yield to
     * [prefillHostAvatar], everything else gets the themed icon).
     */
    private fun installOplusHeaderBadgeApplyReapply(oplusHeader: OplusHeaderMembers) {
        val method = oplusHeader.applyExpandedCanvasIconWithBadge ?: return
        hooks.install(method, "systemui.panel.header.applyExpandedCanvasIconWithBadge") { chain ->
            val result = chain.proceed()
            chain.instanceOrNull?.let { extension ->
                try {
                    oplusHeader.getBase.invoke(extension)?.let { wrapper ->
                        applyPanelIcon(wrapper, target = wrapper.iconTarget)
                    }
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:header_badge_apply",
                        message = "Oplus Header 徽标合成后恢复图标失败",
                        cause = exception,
                        revision = configuration.snapshot.revision,
                    )
                }
            }
            result
        }
    }

    /**
     * ColorOS 17 moved the header refresh of every Oplus template wrapper (messaging, big text,
     * media, call, big picture) into `proxyOnContentUpdated(ExpandableNotificationRow)`, which those
     * wrappers call directly instead of going through `NotificationHeaderViewWrapper.onContentUpdated`.
     * Our base-class hook therefore never fires for an in-place content update — a new WeChat
     * message, a media title change — and the host's own pass leaves `mIcon` showing the raw
     * `smallIcon` again. Re-apply right after the proxy returns, which is where the host has just
     * finished rewriting the icon tag.
     */
    private fun installOplusHeaderContentUpdatedReapply(oplusHeader: OplusHeaderMembers) {
        val method = oplusHeader.proxyOnContentUpdated ?: return
        hooks.install(method, "systemui.panel.header.proxyOnContentUpdated") { chain ->
            val result = chain.proceed()
            chain.instanceOrNull?.let { extension ->
                try {
                    oplusHeader.getBase.invoke(extension)?.let { wrapper ->
                        applyPanelIcon(wrapper)
                    }
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:oplus_header_content_updated",
                        message = "Oplus Header 内容更新后恢复图标失败",
                        cause = exception,
                        revision = configuration.snapshot.revision,
                    )
                }
            }
            result
        }
    }

    private fun installOplusHeaderColorGuard(oplusHeader: OplusHeaderMembers) {
        val method = oplusHeader.updateIconColor ?: return
        val getIcon = members.headerGetIcon ?: return
        hooks.install(method, "systemui.panel.header.updateIconColor") { chain ->
            val snapshot = configuration.snapshot
            if (!snapshot.config.panelIconReplacementEnabled) return@install chain.proceed()
            val extension = chain.instanceOrNull ?: return@install chain.proceed()
            val wrapper = try {
                oplusHeader.getBase.invoke(extension)
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "panel:header_color_guard",
                    message = "Oplus Header 二次着色保护失败，交回原实现",
                    cause = exception,
                    revision = snapshot.revision,
                )
                null
            } ?: return@install chain.proceed()
            val icon = getIcon.invoke(wrapper) as? ImageView ?: return@install chain.proceed()
            val iconIdentifier = try {
                val row = members.notificationViewWrapperRow.get(wrapper)
                val sbn = row?.let(::statusBarNotificationFromRow)
                val drawable = icon.drawable
                if (
                    sbn != null &&
                    drawable != null &&
                    headerIconClaims.isCurrentClaim(icon, sbn.key, drawable)
                ) sbn.key else null
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "panel:header_color_guard",
                    message = "Oplus Header 二次着色保护失败，交回原实现",
                    cause = exception,
                    revision = snapshot.revision,
                )
                null
            }
            val result = if (iconIdentifier != null) false else chain.proceed()
            // The host tints the *drawable itself* with SRC_ATOP (see updateIconColor's bytecode),
            // so clearing the ImageView-level filter cannot undo it, and whether it tints at all
            // depends on a grayscale tag it only refreshes when the small-icon instance changes.
            // For the second notification of one app the tag still describes the first one, so the
            // themed icon we just wrote comes back as a solid silhouette. Re-applying after the
            // host returns is idempotent when the claim already matched (same drawable, same key)
            // and restores the themed icon when it did not, exactly like the roundness hook below.
            if (iconIdentifier == null) {
                try {
                    applyPanelIcon(wrapper)
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:header_color_reapply",
                        message = "Oplus Header 二次着色后恢复图标失败",
                        cause = exception,
                        revision = snapshot.revision,
                    )
                }
            }
            result
        }
    }

    private fun installOplusHeaderRoundnessReapply(oplusHeader: OplusHeaderMembers) {
        val method = oplusHeader.updateIconRoundness ?: return
        hooks.install(method, "systemui.panel.header.updateIconRoundness") { chain ->
            val extension = chain.instanceOrNull
            val wrapper = extension?.let { instance ->
                try {
                    oplusHeader.getBase.invoke(instance)
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:header_roundness_reapply",
                        message = "Oplus Header 圆角处理前读取 wrapper 失败",
                        cause = exception,
                        revision = configuration.snapshot.revision,
                    )
                    null
                }
            }
            // Within a few bytecodes the host asks for this very row's badge drawable
            // (`...getHeaderIconBadgeDrawable(key|pkg)`), so the row is recorded for that lookup.
            wrapper?.let(::statusBarNotificationOfWrapper)?.let { rowSbn -> headerBadgeRow.set(rowSbn) }
            val result = try {
                chain.proceed()
            } finally {
                headerBadgeRow.remove()
            }
            if (wrapper != null) {
                try {
                    applyPanelIcon(wrapper, target = wrapper.iconTarget)
                } catch (exception: Exception) {
                    diagnostics.runtimeFailure(
                        scope = "panel:header_roundness_reapply",
                        message = "Oplus Header 圆角处理后恢复图标失败",
                        cause = exception,
                        revision = configuration.snapshot.revision,
                    )
                }
            }
            result
        }
    }

    /** The notification a `NotificationHeaderViewWrapper` currently renders. */
    private fun statusBarNotificationOfWrapper(wrapper: Any): StatusBarNotification? =
        runCatching { members.notificationViewWrapperRow.get(wrapper) }.getOrNull()
            ?.let(::statusBarNotificationFromRow)

    private fun applyPanelIcon(
        wrapper: Any,
        rowCandidate: Any? = null,
        target: PanelIconTarget = PanelIconTarget.Header,
        expectedSnapshot: RuntimeSnapshot? = null,
        allowDeferredLookup: Boolean = true,
        verifyAfterWrite: Boolean = true,
    ) {
        val snapshot = expectedSnapshot ?: configuration.snapshot
        if (expectedSnapshot != null && !configuration.isCurrent(snapshot)) return
        if (!snapshot.config.panelIconReplacementEnabled) return
        try {
            val row = rowCandidate
                ?: runCatching { members.notificationViewWrapperRow.get(wrapper) }.getOrNull()
                ?: return
            val rowView = row as? View
            val icon = when (target) {
                PanelIconTarget.Header -> members.headerGetIcon?.invoke(wrapper) as? ImageView
                // GroupIconManager colors NotificationHeaderViewWrapper.mIcon (CachingIconView).
                PanelIconTarget.OplusGroupSummary ->
                    (members.headerGetIcon?.invoke(wrapper) as? ImageView)
                        ?: rowView?.findOplusGroupSummaryIcon()
            } ?: run {
                if (target == PanelIconTarget.OplusGroupSummary && allowDeferredLookup && rowView != null) {
                    rowView.post {
                        if (configuration.isCurrent(snapshot)) {
                            applyPanelIcon(
                                wrapper = wrapper,
                                rowCandidate = row,
                                target = target,
                                expectedSnapshot = snapshot,
                                allowDeferredLookup = false,
                            )
                        }
                    }
                }
                return
            }
            if (target == PanelIconTarget.Header) headerIconClaims.release(icon)
            val sbn = statusBarNotificationFromRow(row) ?: return
            // "Hide notification content" shows the public (redacted) layout instead of the avatar
            // composition; handle the icon there and stop before the avatar yield runs.
            if (target == PanelIconTarget.Header && applyHiddenContentIcon(row)) return
            // The collapsed group row is the app's own summary and never carries a contact avatar,
            // so it keeps the configured icon source instead of yielding to the host.
            val plan = if (target == PanelIconTarget.OplusGroupSummary) {
                snapshot.resolver.resolveGroupSummaryIconPlan(
                    context = icon.context,
                    sbn = sbn,
                    originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
                )
            } else {
                snapshot.resolver.resolvePanelIconPlan(
                    context = icon.context,
                    sbn = sbn,
                    originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
                )
            }
            if (plan == null) {
                // Yielding to the host avatar. ColorOS composes that avatar asynchronously, so the
                // row would paint the plain small icon first; pre-compose it here instead.
                if (target == PanelIconTarget.Header) {
                    prefillHostAvatar(icon, wrapper, sbn, snapshot)
                    // plan is null here only when the theme engine itself failed (a yield would
                    // have produced a replacement plan for every no-avatar row since 5.4). The
                    // guard stays as the cold-start net: it resolves the themed icon lazily and
                    // forces redraws until the engine is warm, then takes the slot from the
                    // app-icon placeholder the host painted in the meantime.
                    guardHeaderIconAgainstHostPlaceholder(icon, row, sbn, snapshot)
                }
                // An aggregated row that just yielded still has to end up with *something*: when
                // the host has no photo to compose it paints its own monochrome artwork, and for
                // SMS that is the blank white bubble that also tints the whole pill white. Watch
                // the row and put the theme icon back only for those blank results, so a real
                // sender photo on an aggregated row is never touched.
                if (target == PanelIconTarget.OplusGroupSummary) {
                    guardSummaryIconAgainstBlank(icon, row, sbn, snapshot)
                }
                return
            }

            icon.applyRenderPlan(plan, target)
            if (target == PanelIconTarget.OplusGroupSummary) {
                reportGroupSummaryAnchor(icon, row, sbn)
                writeAlternateGroupBadge(icon, row, sbn, snapshot, plan)
            }
            if (target == PanelIconTarget.Header) {
                icon.drawable?.let { drawable -> headerIconClaims.claim(icon, sbn.key, drawable) }
            }
            // The collapsed group summary is repainted by GroupIconManager's own coroutine on
            // ColorOS 17, so it races the write exactly like the header's async small icon does.
            if (verifyAfterWrite) {
                verifyIconAfterWrite(
                    icon = icon,
                    wrapper = wrapper,
                    row = row,
                    sbn = sbn,
                    snapshot = snapshot,
                    target = target,
                    reapplyPlan = if (target == PanelIconTarget.OplusGroupSummary) plan else null,
                )
                if (target == PanelIconTarget.OplusGroupSummary) {
                    keepGroupSummaryIcon(icon, row, sbn, snapshot) {
                        // Re-write the plan resolved above instead of re-running [applyPanelIcon].
                        // The resolver answers null as soon as the row starts carrying a child
                        // avatar, and a re-resolve would then write nothing at all — leaving the
                        // host's raw badge on screen, which is what the guard exists to prevent.
                        icon.applyRenderPlan(plan, target)
                    }
                }
            }
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "panel:replace_icon:${target.diagnosticName}",
                message = "通知面板规则图标注入失败，保留 ColorOS 原结果",
                cause = exception,
                revision = snapshot.revision,
            )
        }
    }

    /**
     * ColorOS 的 `CachingIconView` 会在 `setTag(ImageTransformState, smallIcon)` 之后异步加载
     * smallIcon 并再次 `setImageDrawable`；折叠分组的摘要图标则由 `GroupIconManager` 的协程
     * 重绘。这两处回填如果晚于模块写入，行上就会退回宿主的灰度 smallIcon —— 表现为同应用里
     * 个别通知、或短信自动分组那一行是单色/空白剪影，其它行正常，与包名和通知内容无关，
     * 是纯时序竞争。
     *
     * 单次校验会输给更慢的宿主：日志里 `icon.reapplied` 与仍未替换的行同时存在，说明回填窗口
     * 可以晚于 256ms。因此这里按 256 / 512 / 1024 / 2048ms 递增复查 [MAX_ICON_VERIFY_PASSES] 轮，
     * 每轮只在 drawable 已不是我们写的那张时补铺，实例一致就安静地进入下一轮。
     *
     * 只针对模块接管的行（`plan != null`），所以不会和让路头像互相拉扯 —— 让路时
     * `applyPanelIcon` 会走 `prefillHostAvatar` 重新合成头像而不是写主题图标。
     */
    private fun verifyIconAfterWrite(
        icon: ImageView,
        wrapper: Any,
        row: Any,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
        target: PanelIconTarget,
        pass: Int = 0,
        reapplyPlan: NotificationIconResolver.PanelIconRenderPlan? = null,
    ) {
        if (pass >= MAX_ICON_VERIFY_PASSES) return
        val written = icon.drawable ?: return
        val rowView = row as? View ?: return
        rowView.postDelayed({
            try {
                if (!configuration.isCurrent(snapshot) || !icon.isAttachedToWindow) return@postDelayed
                if (statusBarNotificationFromRow(row)?.key != sbn.key) return@postDelayed
                if (icon.drawable === written) {
                    verifyIconAfterWrite(icon, wrapper, row, sbn, snapshot, target, pass + 1, reapplyPlan)
                    return@postDelayed
                }
                diagnostics.report(
                    level = DiagnosticLevel.Debug,
                    event = DiagnosticEvent.IconReapplied,
                    message = "宿主异步回填 smallIcon 覆盖了主题图标，重铺一次",
                    attributes = mapOf("package" to sbn.packageName, "key" to sbn.key),
                    occurrence = OccurrencePolicy.RateLimited("panel:icon_reapply", 60_000L),
                )
                if (reapplyPlan != null) {
                    // The collapsed-group summary rewrites the bind-time plan for the same reason
                    // [keepGroupSummaryIcon]'s closure does: a re-resolve can flip to null mid-life
                    // and leave the host's own (for SMS, blank white) result on screen.
                    icon.applyRenderPlan(reapplyPlan, target)
                } else {
                    applyPanelIcon(
                        wrapper = wrapper,
                        rowCandidate = row,
                        target = target,
                        expectedSnapshot = snapshot,
                        allowDeferredLookup = false,
                        verifyAfterWrite = false,
                    )
                }
                verifyIconAfterWrite(icon, wrapper, row, sbn, snapshot, target, pass + 1, reapplyPlan)
            } catch (exception: Exception) {
                diagnostics.runtimeFailure(
                    scope = "panel:icon_reapply",
                    message = "重铺被宿主回填的图标失败",
                    cause = exception,
                    revision = snapshot.revision,
                )
            }
        }, PANEL_RETRY_DELAY_MILLIS shl pass)
    }

    /** Guards of the collapsed group rows this hook currently owns, keyed by their icon view. */
    private val groupIconTakeovers = java.util.WeakHashMap<ImageView, IconTakeover>()

    /**
     * Keeps a collapsed group row on the module's icon for as long as the row lives, instead of for
     * the handful of seconds [verifyIconAfterWrite] watches.
     *
     * `GroupIconManager` repaints the badge icon from its own coroutine, and that repaint lands
     * whenever the host gets round to it — on a busy shade it can be well past the last timed pass.
     * The damage is visible and permanent: the row falls back to the monochrome notification mask
     * (SMS: a solid white speech bubble) and the host then derives the pill colour from that white
     * artwork, so the collapsed group ends up as a blank white block next to its count badge.
     *
     * A draw-time guard costs one identity compare per frame and re-applies the icon only when the
     * host actually replaced it, which is why it can outlive the timed ladder at no real cost.
     */
    private fun keepGroupSummaryIcon(
        icon: ImageView,
        row: Any?,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
        reapply: () -> Unit,
    ) {
        groupIconTakeovers.put(icon, IconTakeover(icon, row, sbn, snapshot, reapply))?.stop()
        groupIconTakeovers[icon]?.start()
    }

    private inner class IconTakeover(
        private val icon: ImageView,
        private val row: Any?,
        private val sbn: StatusBarNotification,
        private val snapshot: RuntimeSnapshot,
        private val reapply: () -> Unit,
    ) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

        private var applied: Drawable? = null
        private var observer: ViewTreeObserver? = null
        private var reapplies = 0
        private var lastApplyAt = 0L
        private var stopped = false

        fun start() {
            applied = icon.drawable
            observer = icon.viewTreeObserver
            observer?.addOnPreDrawListener(this)
            icon.addOnAttachStateChangeListener(this)
        }

        fun stop() {
            if (stopped) return
            stopped = true
            runCatching { observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this) }
            runCatching { icon.removeOnAttachStateChangeListener(this) }
            if (groupIconTakeovers[icon] === this) groupIconTakeovers.remove(icon)
        }

        override fun onPreDraw(): Boolean {
            if (stopped) return true
            if (!icon.isAttachedToWindow || !configuration.isCurrent(snapshot)) {
                stop()
                return true
            }
            val key = runCatching { row?.let { statusBarNotificationFromRow(it) }?.key }.getOrNull()
            if (key != sbn.key) {
                stop()
                return true
            }
            val expected = applied
            if (expected == null) {
                stop()
                return true
            }
            if (icon.drawable === expected) return true
            // Never hand the row back to the host. Giving up used to be the only way out of a
            // fight loop, but the host's last write is what stays on screen: the row then keeps a
            // blank white composition until SystemUI restarts, which is exactly the "聚合图标又
            // 变白了，重启后恢复" report. Re-applying at draw time is one identity compare per
            // frame, so the guard simply stays; past [MAX_GROUP_ICON_TAKEOVERS] it goes quiet and
            // throttles to one repair per [TAKEOVER_QUIET_INTERVAL_MILLIS] to keep the repaint
            // loop bounded instead of spinning on every frame.
            val now = SystemClock.uptimeMillis()
            if (reapplies >= MAX_GROUP_ICON_TAKEOVERS &&
                now - lastApplyAt < TAKEOVER_QUIET_INTERVAL_MILLIS
            ) {
                return true
            }
            if (reapplies == MAX_GROUP_ICON_TAKEOVERS) {
                diagnostics.report(
                    level = DiagnosticLevel.Warning,
                    event = DiagnosticEvent.IconReapplied,
                    message = "宿主反复回填原始图标，改为限速补铺（持续保持主题图标）",
                    attributes = mapOf("package" to sbn.packageName, "key" to sbn.key),
                    occurrence = OccurrencePolicy.Once("panel:group_icon_takeover_throttled:${sbn.key}"),
                )
            }
            reapplies++
            lastApplyAt = now
            if (reapplies <= MAX_GROUP_ICON_TAKEOVERS) {
                diagnostics.report(
                    level = DiagnosticLevel.Debug,
                    event = DiagnosticEvent.IconReapplied,
                    message = "宿主在绘制前换回了原始图标，补铺聚合摘要图标",
                    attributes = mapOf(
                        "package" to sbn.packageName,
                        "key" to sbn.key,
                        "pass" to reapplies.toString(),
                    ),
                    occurrence = OccurrencePolicy.RateLimited("panel:group_icon_takeover", 60_000L),
                )
            }
            reapply()
            applied = icon.drawable
            return true
        }

        override fun onViewAttachedToWindow(view: View) {
            if (stopped) return
            // Rows are detached and re-attached whenever the shade re-lays itself out (collapse,
            // expand, reorder). Detach removed the draw listener; without re-attaching it here the
            // guard is gone for good while the row keeps living — the host then paints its cached
            // composition (for SMS the blank white sheet) and nothing ever takes the slot back.
            // The re-attach itself is also the first suspicious frame: check the artwork right away.
            observer = icon.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            if (icon.drawable !== applied) onPreDraw()
        }

        override fun onViewDetachedFromWindow(view: View) {
            // Keep the guard alive: a row detaches on every shade re-layout, and a guard that
            // dies here never comes back. Only remove the draw listener; stop() stays reserved
            // for the real exits (key swap, config swap, replacement by a newer guard).
            runCatching { observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this) }
            observer = null
        }
    }

    /** Blank-sheet repairs currently watching a collapsed group row, keyed by its icon view. */
    private val summaryBlankGuards = java.util.WeakHashMap<ImageView, SummaryBlankGuard>()

    /**
     * Keeps a collapsed group row from staying on a blank sheet the host painted.
     *
     * The row reaches this path whenever the module has nothing to replace it with: the aggregate
     * carries a sender photo (so the host composes "avatar + badge") or the theme icon could not be
     * resolved. Both are fine on their own — the problem is the fallback. With no photo to compose,
     * ColorOS paints the app's monochrome small icon, which for SMS is a solid white bubble; the
     * host then takes the pill colour from that artwork, and the row becomes a blank white squircle
     * beside its count badge until SystemUI restarts (a restart rebinds every row, which is why it
     * always looks "fixed" afterwards).
     *
     * So the host keeps the decision, and this guard only steps in when the result is measurably
     * empty: a flat white fill, or the app's own launcher icon in the avatar slot. A real photo is
     * left exactly as the host drew it.
     */
    private fun guardSummaryIconAgainstBlank(
        icon: ImageView,
        row: Any?,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
    ) {
        // The theme icon can be a transient blank right after the shade opens — the engine hands
        // back the icon before its background layer is ready, which [ThemeIconProvider] rejects, so
        // there is nothing to write here *yet*. Attach the guard anyway: it re-resolves on each
        // repair and a delayed refill keeps trying until the icon is ready, then hands off to the
        // IconTakeover that holds it for good.
        summaryBlankGuards.put(icon, SummaryBlankGuard(icon, row, sbn, snapshot))?.stop()
        summaryBlankGuards[icon]?.start()
        scheduleGroupSummaryRefill(icon, row, sbn, snapshot)
    }

    /** Header-slot guards that take the icon back from a host app-icon placeholder. */
    private val headerPlaceholderGuards = java.util.WeakHashMap<ImageView, HeaderPlaceholderGuard>()

    /**
     * Cold-start net for the header icon slot.
     *
     * Since 5.4 a row without a large icon is replaced deterministically at bind time, so this
     * guard only runs when the bind itself could not produce a plan — the theme engine was still
     * cold and the resolver returned nothing. It watches the slot and puts the theme icon over the
     * app-icon placeholder the host painted in the meantime, forcing redraws until the engine is
     * warm. A real photo (or the host's "photo + badge" composition, whose centre is the photo)
     * stops the guard for good, so a row that does get its avatar later is never painted over.
     *
     * The precondition is "no avatar to protect" rather than the exact `no-large-icon` verdict: the
     * slot can also be tied up by a launcher-icon placeholder or a blank sheet from the host, and
     * those are the same cold-start casualty. [HeaderPlaceholderGuard.isHostAppIconPlaceholder]
     * still refuses anything that is not measurably empty artwork, so a late avatar is never
     * covered.
     */
    private fun guardHeaderIconAgainstHostPlaceholder(
        icon: ImageView,
        row: Any?,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
    ) {
        val context = icon.context
        if (HostAvatarPresence.evaluate(context, sbn).renderable) return
        // No themed lookup here on purpose: the engine is cold, that is why this path is running
        // at all. The guard resolves the themed icon lazily on each draw instead.
        headerPlaceholderGuards
            .put(icon, HeaderPlaceholderGuard(icon, row, sbn, snapshot))
            ?.stop()
        headerPlaceholderGuards[icon]?.start()
    }

    private inner class HeaderPlaceholderGuard(
        private val icon: ImageView,
        private val row: Any?,
        private val sbn: StatusBarNotification,
        private val snapshot: RuntimeSnapshot,
    ) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

        private var observer: ViewTreeObserver? = null
        private var writes = 0
        private var lastWriteAt = 0L
        private var stopped = false

        /** Resolved on first success; the theme engine can be cold for the first seconds. */
        private var themed: Drawable? = null

        /** Draw passes forced while the themed icon is still unresolved. */
        private var nudges = 0

        fun start() {
            observer = icon.viewTreeObserver
            observer?.addOnPreDrawListener(this)
            icon.addOnAttachStateChangeListener(this)
            nudge()
        }

        /**
         * The guard only acts inside OnPreDraw, which fires when something invalidates the view.
         * A bound row that nobody redraws would never trigger it, so schedule a short ladder of
         * draw passes — the same idea as the group-summary refill ladder. Six passes reach roughly
         * sixteen seconds, which outlasts the theme engine's own warm-up after a SystemUI restart.
         */
        private fun nudge() {
            if (stopped || nudges >= 6) return
            val delay = 256L shl nudges
            nudges++
            icon.postDelayed({
                if (!stopped && themed == null) {
                    icon.invalidate()
                    nudge()
                }
            }, delay)
        }

        fun stop() {
            if (stopped) return
            stopped = true
            runCatching { observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this) }
            runCatching { icon.removeOnAttachStateChangeListener(this) }
            if (headerPlaceholderGuards[icon] === this) headerPlaceholderGuards.remove(icon)
        }

        override fun onPreDraw(): Boolean {
            if (stopped) return true
            if (!icon.isAttachedToWindow || !configuration.isCurrent(snapshot)) {
                stop()
                return true
            }
            val key = runCatching { row?.let { statusBarNotificationFromRow(it) }?.key }.getOrNull()
            if (key != sbn.key) {
                stop()
                return true
            }
            val themedNow = themed ?: snapshot.resolver
                .resolveThemeDrawableByPackage(icon.context, sbn.packageName)
                ?.also {
                    themed = it
                    writes = 0
                    lastWriteAt = 0L
                }
                ?: return true
            val current = icon.drawable ?: return true
            if (current === themedNow) return true
            // Anything else on screen is the host's own content — a contact photo, or the
            // "photo + badge" composition it built around one. That wins; the guard is done.
            if (!isHostAppIconPlaceholder(current)) {
                stop()
                return true
            }
            val now = SystemClock.uptimeMillis()
            if (writes >= MAX_GROUP_ICON_TAKEOVERS &&
                now - lastWriteAt < TAKEOVER_QUIET_INTERVAL_MILLIS
            ) {
                return true
            }
            writes++
            lastWriteAt = now
            if (writes == 1) {
                diagnostics.report(
                    level = DiagnosticLevel.Info,
                    event = DiagnosticEvent.HostAvatarReplace,
                    message = "角标诊断：${sbn.packageName} 宿主的头像位里只有应用图标，改用主题图标",
                    attributes = mapOf("package" to sbn.packageName, "key" to sbn.key),
                    occurrence = OccurrencePolicy.Once("header_placeholder_guard:${sbn.key}"),
                )
            }
            icon.clearColorFilter()
            icon.imageTintList = null
            icon.setImageDrawable(themedNow)
            return true
        }

        private fun isHostAppIconPlaceholder(drawable: Drawable): Boolean =
            HostAvatarPresence.looksLikeLauncherIcon(icon.context, sbn.packageName, drawable) ||
                HostAvatarPresence.looksBlank(drawable) ||
                HostAvatarPresence.looksLikeMonochromeMask(drawable)

        override fun onViewAttachedToWindow(view: View) {
            if (stopped) return
            observer = icon.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            onPreDraw()
        }

        override fun onViewDetachedFromWindow(view: View) {
            // Same policy as IconTakeover: survive shade re-layouts, re-arm on the next attach.
            runCatching { observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this) }
            observer = null
        }
    }

    /**
     * Re-resolves the group summary theme icon on a short ladder after a bind that found nothing.
     *
     * The theme engine returns a blank sheet for a second or two after the shade opens; the row
     * reaches the null-plan branch because of exactly that, so without this retry it stays on the
     * host's white mask until SystemUI restarts. Each step re-resolves — a cache hit once the icon
     * is ready — and the first non-null result writes the icon and installs the IconTakeover that
     * holds it afterwards, so the remaining steps short-circuit.
     */
    private fun scheduleGroupSummaryRefill(
        icon: ImageView,
        row: Any?,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
    ) {
        GROUP_SUMMARY_REFILL_DELAYS_MILLIS.forEach { delay ->
            icon.postDelayed({
                if (!configuration.isCurrent(snapshot) || !icon.isAttachedToWindow) return@postDelayed
                // An IconTakeover already holding the slot means a later bind wrote the theme icon;
                // nothing left to refill.
                if (groupIconTakeovers[icon] != null) return@postDelayed
                val plan = snapshot.resolver.resolveGroupSummaryIconPlan(
                    context = icon.context,
                    sbn = sbn,
                    originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
                ) ?: return@postDelayed
                icon.applyRenderPlan(plan, PanelIconTarget.OplusGroupSummary)
                keepGroupSummaryIcon(icon, row, sbn, snapshot) {
                    icon.applyRenderPlan(plan, PanelIconTarget.OplusGroupSummary)
                }
            }, delay)
        }
    }

    private inner class SummaryBlankGuard(
        private val icon: ImageView,
        private val row: Any?,
        private val sbn: StatusBarNotification,
        private val snapshot: RuntimeSnapshot,
    ) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

        private var observer: ViewTreeObserver? = null
        private var inspected: Drawable? = null
        private var written: Drawable? = null
        private var repairs = 0
        private var lastRepairAt = 0L
        private var stopped = false

        fun start() {
            observer = icon.viewTreeObserver
            observer?.addOnPreDrawListener(this)
            icon.addOnAttachStateChangeListener(this)
        }

        fun stop() {
            if (stopped) return
            stopped = true
            runCatching { observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this) }
            runCatching { icon.removeOnAttachStateChangeListener(this) }
            if (summaryBlankGuards[icon] === this) summaryBlankGuards.remove(icon)
        }

        /**
         * One identity compare per frame; the expensive artwork test only runs when the host
         * actually swapped the drawable, so scrolling the shade stays cheap.
         */
        override fun onPreDraw(): Boolean {
            if (stopped) return true
            if (!icon.isAttachedToWindow || !configuration.isCurrent(snapshot)) {
                stop()
                return true
            }
            val key = runCatching { row?.let { statusBarNotificationFromRow(it) }?.key }.getOrNull()
            if (key != sbn.key) {
                stop()
                return true
            }
            val current = icon.drawable ?: return true
            // Once the IconTakeover (installed by the refill once the theme icon is ready) owns the
            // slot, this guard has nothing left to watch.
            if (groupIconTakeovers[icon] != null) {
                stop()
                return true
            }
            if (current === written || current === inspected) return true
            inspected = current
            val blank = HostAvatarPresence.looksBlank(current)
            val mask = HostAvatarPresence.looksLikeMonochromeMask(current)
            val placeholder = HostAvatarPresence.looksLikeLauncherIcon(
                context = icon.context,
                packageName = sbn.packageName,
                drawable = current,
            )
            if (!blank && !mask && !placeholder) return true
            val now = SystemClock.uptimeMillis()
            if (repairs >= MAX_GROUP_ICON_TAKEOVERS &&
                now - lastRepairAt < TAKEOVER_QUIET_INTERVAL_MILLIS
            ) {
                return true
            }
            // Re-resolve on each repair: the theme icon may have been a transient blank at bind,
            // and [ThemeIconProvider] refuses to cache one, so a later lookup can still succeed.
            val themed = snapshot.resolver.resolveThemeDrawableByPackage(icon.context, sbn.packageName)
            if (themed == null) return true
            lastRepairAt = now
            repairs++
            if (repairs == 1) {
                diagnostics.report(
                    level = DiagnosticLevel.Debug,
                    event = DiagnosticEvent.PackageIconResolved,
                    message = "聚合摘要被宿主画成空白，补铺主题图标",
                    attributes = mapOf(
                        "package" to sbn.packageName,
                        "key" to sbn.key,
                        "blank" to blank.toString(),
                        "mask" to mask.toString(),
                        "placeholder" to placeholder.toString(),
                    ),
                    occurrence = OccurrencePolicy.Once("panel:summary_blank_repair:${sbn.key}"),
                )
            }
            icon.imageTintList = null
            icon.colorFilter = null
            icon.setImageDrawable(themed)
            written = themed
            return true
        }

        override fun onViewAttachedToWindow(view: View) {
            if (stopped) return
            observer = icon.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            if (icon.drawable !== written) onPreDraw()
        }

        override fun onViewDetachedFromWindow(view: View) {
            // Same policy as IconTakeover: shade re-layouts detach rows constantly; the guard
            // must survive them and re-arm on the next attach.
            runCatching { observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this) }
            observer = null
        }
    }

    private fun applyGroupSummaryIcon(iconView: ImageView, entry: Any, snapshot: RuntimeSnapshot) {
        if (!configuration.isCurrent(snapshot)) return
        try {
            val sbn = members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification ?: return
            val plan = snapshot.resolver.resolveGroupSummaryIconPlan(
                context = iconView.context,
                sbn = sbn,
                originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
            )
            if (plan == null) {
                // Nothing to write: the host keeps the row, and the guard only intervenes if what
                // it paints is empty artwork.
                guardSummaryIconAgainstBlank(iconView, entry, sbn, snapshot)
                return
            }
            iconView.applyRenderPlan(plan, PanelIconTarget.OplusGroupSummary)
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "panel:replace_icon:${PanelIconTarget.OplusGroupSummary.diagnosticName}",
                message = "通知面板规则图标注入失败，保留 ColorOS 原结果",
                cause = exception,
                revision = snapshot.revision,
            )
        }
    }

    /**
     * ColorOS hides the "contact photo + app-icon badge" composition behind
     * `withContext(Dispatchers.Default)` → `createBackgroundWithAppIcon(...)`. Until that coroutine
     * comes back, the row shows the plain `smallIcon`, which reads as "先闪一下应用图标再跳成
     * 头像". Composing the same picture synchronously here fills that window: the host result that
     * arrives a frame or two later is the same visuals, so nothing jumps. Failures (missing helper,
     * no avatar, unknown size) simply leave the host's own first frame in place.
     */
    private fun prefillHostAvatar(
        icon: ImageView,
        wrapper: Any,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
    ) {
        try {
            val size = headerContentSizePx(wrapper, icon)
            if (size <= 0) return
            // Aggregated rows ("[2条] …") are summaries, and ColorOS never composes an avatar for
            // them — but WeChat/WeCom service accounts still ship their own picture there. Compose
            // it here for those rows as well, otherwise the row keeps the app icon for as long as
            // the host's coroutine runs (the visible "原生图标一闪而过").
            val summary = HostAvatarPresence.isGroupSummary(sbn)
            val drawable = snapshot.resolver.composeHostAvatarPreview(
                context = icon.context,
                sbn = sbn,
                contentSizePx = size,
                allowGroupSummaryAvatar = summary,
            )
            if (drawable == null) {
                // The sender photo can land a beat after the row is first bound: WeChat posts the
                // notification before the avatar is ready, so the extras probe misses and the host
                // paints the plain app icon until its own composition lands. Retry briefly instead
                // of waiting for that coroutine — the row then turns into the avatar within a
                // couple of hundred milliseconds instead of "一闪而过".
                val verdict = HostAvatarPresence.evaluate(icon.context, sbn, summary)
                diagnostics.report(
                    level = DiagnosticLevel.Debug,
                    event = DiagnosticEvent.HostAvatarPrefill,
                    message = "头像暂不可用（${verdict.reason}），稍后补铺",
                    attributes = mapOf(
                        "package" to sbn.packageName,
                        "key" to sbn.key,
                        "verdict" to verdict.reason,
                        "difference" to verdict.difference.toString(),
                        "summary" to summary.toString(),
                    ),
                    occurrence = OccurrencePolicy.Once("panel:prefill_deferred:${sbn.key}"),
                )
                scheduleAvatarRefill(icon, wrapper, sbn, snapshot, 0)
                return
            }
            lastHeaderContentSizePx = size
            icon.setImageDrawable(drawable)
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.HostAvatarPrefill,
                message = if (summary) {
                    "宿主异步合成头像前先铺一帧同构图（聚合摘要），避免应用图标闪现"
                } else {
                    "宿主异步合成头像前先铺一帧同构图，避免应用图标闪现"
                },
                attributes = mapOf(
                    "package" to sbn.packageName,
                    "size" to size.toString(),
                    "summary" to summary.toString(),
                ),
                occurrence = OccurrencePolicy.Once("panel:host_avatar_prefill:${sbn.packageName}"),
            )
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "panel:host_avatar_prefill",
                message = "头像预合成失败，保留宿主第一帧",
                cause = exception,
                revision = snapshot.revision,
            )
        }
    }

    /**
     * Retries the avatar composition for a row whose photo was not available yet, so the app icon
     * the host painted on the first frame is replaced as soon as the picture exists instead of
     * whenever the host's own coroutine happens to finish. Stops as soon as one attempt succeeds,
     * when the row is detached, or when the configuration changed underneath.
     *
     * The notification is re-read from the row on every attempt, never taken from the argument:
     * WeChat re-posts the message when it has loaded the sender photo, and that is a *new*
     * `StatusBarNotification` with a new large icon. Evaluating the captured object forever is why
     * a row judged `no-large-icon` on arrival stayed on the app icon until it was rebound.
     */
    private fun scheduleAvatarRefill(
        icon: ImageView,
        wrapper: Any,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
        attempt: Int,
    ) {
        if (attempt >= AVATAR_REFILL_DELAYS_MILLIS.size) return
        icon.postDelayed({
            try {
                if (!configuration.isCurrent(snapshot)) return@postDelayed
                if (!icon.isAttachedToWindow) return@postDelayed
                // A row can be recycled onto another notification while we wait; that row is no
                // longer ours to fill.
                val current = statusBarNotificationOfWrapper(wrapper)
                if (current == null || current.key != sbn.key) return@postDelayed
                val size = headerContentSizePx(wrapper, icon)
                if (size <= 0) {
                    scheduleAvatarRefill(icon, wrapper, current, snapshot, attempt + 1)
                    return@postDelayed
                }
                val summary = HostAvatarPresence.isGroupSummary(current)
                val drawable = snapshot.resolver.composeHostAvatarPreview(
                    context = icon.context,
                    sbn = current,
                    contentSizePx = size,
                    allowGroupSummaryAvatar = summary,
                )
                if (drawable == null) {
                    scheduleAvatarRefill(icon, wrapper, current, snapshot, attempt + 1)
                    return@postDelayed
                }
                lastHeaderContentSizePx = size
                icon.setImageDrawable(drawable)
                diagnostics.report(
                    level = DiagnosticLevel.Debug,
                    event = DiagnosticEvent.HostAvatarPrefill,
                    message = "头像到位，补铺第 ${attempt + 1} 次成功",
                    attributes = mapOf(
                        "package" to current.packageName,
                        "key" to current.key,
                        "size" to size.toString(),
                        "summary" to summary.toString(),
                    ),
                    occurrence = OccurrencePolicy.Once("panel:avatar_refill:${current.key}"),
                )
            } catch (_: Throwable) {
                // A detached or recycled row is the normal case here; nothing to report.
            }
        }, AVATAR_REFILL_DELAYS_MILLIS[attempt])
    }

    /**
     * `resolveHeaderIconContentSizePx()` is the very value the host boxes into its composer; the
     * laid-out width is only a fallback for builds that renamed it.
     *
     * A freshly bound row is not measured yet, so both can come back as zero — and a zero size used
     * to abort the avatar prefill silently, which is why a whole session can log yields and not a
     * single prefill: the row then keeps the host's "app icon + badge" composition, and the avatar
     * only appears once the row is rebound (expanding the group). Fall back to the size the last
     * composition used, and then to the density-derived default, so the composer always has a box.
     */
    private fun headerContentSizePx(wrapper: Any, icon: ImageView): Int {
        resolveContentSizeMethod(wrapper)?.let { method ->
            runCatching { method.invoke(wrapper) as Int }.getOrNull()
                ?.takeIf { it > 0 }
                ?.let { return it }
        }
        return when {
            icon.width > 0 -> icon.width
            lastHeaderContentSizePx > 0 -> lastHeaderContentSizePx
            else -> (icon.resources.displayMetrics.density * FALLBACK_CONTENT_SIZE_DP).toInt()
        }
    }

    private fun resolveContentSizeMethod(wrapper: Any): Method? {
        contentSizeMethod?.let { return it }
        if (contentSizeMethodResolved) return null
        contentSizeMethodResolved = true
        return runCatching {
            wrapper.javaClass.getMethod("resolveHeaderIconContentSizePx").also { contentSizeMethod = it }
        }.getOrNull()
    }

    private fun statusBarNotificationFromRow(row: Any): StatusBarNotification? {
        val entry = members.expandableRowGetEntry.invoke(row) ?: return null
        return members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification
    }

    private val Any.iconTarget: PanelIconTarget
        get() = if (members.oplusGroupWrapper?.isInstance(this) == true) {
            PanelIconTarget.OplusGroupSummary
        } else {
            PanelIconTarget.Header
        }

    private fun View.findOplusGroupSummaryIcon(): ImageView? {
        val headerId = systemUiId("oplus_notification_collapsed_group_header")
        val containerId = systemUiId("icon_container")
        val iconId = systemUiId("icon")
        if (containerId == 0 || iconId == 0) return null
        val header = headerId.takeIf { it != 0 }?.let { findViewById<View>(it) }
        // ColorOS 17 drops the collapsed-group header id on some builds; the badge then lives in the
        // row's own icon container. Falling back to it keeps the takeover pointed at a view that is
        // actually drawn instead of silently returning null.
        val container = header?.findViewById<View>(containerId) as? ViewGroup
            ?: findViewById<View>(containerId) as? ViewGroup
        return (container ?: header)?.findViewById<View>(iconId) as? ImageView
    }

    @SuppressLint("DiscouragedApi") // These resources belong to the hooked SystemUI APK, not this module.
    private fun View.systemUiId(name: String): Int =
        systemUiIdCache[name] ?: resources.getIdentifier(name, "id", SystemPackages.SYSTEM_UI)
            .also { systemUiIdCache[name] = it }

    private fun ImageView.applyRenderPlan(
        plan: NotificationIconResolver.PanelIconRenderPlan,
        target: PanelIconTarget,
    ) {
        clearHostDecoration(target)
        if (target == PanelIconTarget.OplusGroupSummary) {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = false
        }
        setImageDrawable(plan.drawable)
        plan.tintColor?.let { tint ->
            colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun ImageView.clearHostDecoration(target: PanelIconTarget) {
        background = null
        foreground = null
        clipToOutline = false
        imageTintList = null
        clearColorFilter()

        if (target != PanelIconTarget.OplusGroupSummary) return
        val container = parent as? View
        if (container?.id == systemUiId("icon_container")) {
            container.background = null
            container.foreground = null
            container.clipToOutline = false
            if (container is ViewGroup) {
                container.clipChildren = false
                container.clipToPadding = false
            }
        }
    }

    private enum class PanelIconTarget(val diagnosticName: String) {
        Header("header"),
        OplusGroupSummary("oplus_group_summary"),
    }

    private companion object {
        const val PANEL_RETRY_DELAY_MILLIS = 256L

        /**
         * How many times a written icon is re-checked while the host may still repaint it. Delays
         * double every pass (256 / 512 / 1024 / 2048 ms), so this covers roughly four seconds — the
         * window `CachingIconView` and the avatar coroutine were observed to land in.
         */
        const val MAX_ICON_VERIFY_PASSES = 4

        /**
         * How many repair passes the draw-time guard logs before it goes quiet. It never stops
         * guarding: the host repaints a collapsed group from its own coroutine, and whichever
         * write lands last is what the user sees, so bailing out leaves the row on the host's
         * blank composition until SystemUI restarts.
         */
        const val MAX_GROUP_ICON_TAKEOVERS = 12

    /** Repair interval once the guard is past its logged burst, to keep repaints bounded. */
    const val TAKEOVER_QUIET_INTERVAL_MILLIS = 200L

    /**
     * Retry ladder for a group summary that bound before its theme icon was ready. The engine returns
     * a blank sheet for a second or two after the shade opens, so the first resolve finds nothing;
     * these steps span roughly four seconds, after which the icon is either ready and held by the
     * IconTakeover, or genuinely absent and the host keeps its own artwork.
     */
    val GROUP_SUMMARY_REFILL_DELAYS_MILLIS = longArrayOf(256L, 512L, 1024L, 2048L)

        /**
         * Retry schedule for a row whose sender photo was not attached yet when it was first bound.
         * The first few attempts only have to beat the host's own asynchronous composition
         * (observed around 0.7 s after the row appears); the longer tail covers WeChat re-posting
         * the message once its avatar is loaded, which can take several seconds after the first
         * frame. Every attempt re-reads the notification, so a later post is picked up here long
         * before the row would otherwise be rebound (expand / scroll).
         */
        val AVATAR_REFILL_DELAYS_MILLIS = longArrayOf(180L, 320L, 600L, 1200L, 2000L, 3000L)

        /**
         * Header icon box used when neither the host's own resolver nor the layout has an answer —
         * 40dp is the size the ColorOS 17 headers compose their avatars into at every density.
         */
        const val FALLBACK_CONTENT_SIZE_DP = 40f
    }
}
