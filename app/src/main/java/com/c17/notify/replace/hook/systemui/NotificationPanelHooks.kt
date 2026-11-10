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
import com.c17.notify.replace.hook.icon.HostAvatarPackages
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

    /**
     * The `OplusNotificationHeaderViewWrapperExImp` behind each header wrapper, plus the boolean it
     * passes to `updateIconRoundness` — recorded by [installOplusHeaderRoundnessReapply].
     *
     * `updateIconRoundness` is the host's own entry into "avatar + badge": it asks
     * `NotificationIconBadgePackages.contains(pkg)`, looks the composition up in the header cache,
     * schedules the asynchronous pass when there is none, and applies the result. Re-running it is
     * therefore the cheapest way to make the host retry a row it left on the raw app icon — no
     * module-drawn picture involved. Weak keys so dismissed rows do not pin their wrappers.
     */
    private val oplusExtensions =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, OplusExtensionBinding>())

    /** Keys already covered by the retrigger ladder, so re-entrant binds do not stack new ones. */
    private val avatarRetriggers = ConcurrentHashMap<String, Long>()

    /** `OplusNotificationHeaderViewWrapperExImp.resolveHeaderIconContentSizePx()`, resolved once. */
    @Volatile
    private var contentSizeMethod: Method? = null

    /**
     * The box size the header icon is composed into, remembered from the last ask. Composing an
     * avatar needs it, and a freshly bound row has neither a laid-out width nor a resolvable
     * wrapper yet.
     */
    @Volatile
    private var lastHeaderContentSizePx = 0

    /** See [oplusExtensions]: the host extender and the argument its bind used. */
    private class OplusExtensionBinding(val extension: Any, val roundness: Boolean)

    fun install() {
        installOplusHeaderHooks()
        installHostCompositionHook()
        installGroupIconManagerHooks()
        installPackageIconResolverHooks()
        installBadgePackagesColdStartBridge()
        installPackageIconByNameHooks()
        installHiddenContentHooks()
        installChildrenContainerHooks()
        installStackLayoutSweep()
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
     *
     * The same callback also marks the way *back*: on unlock the row leaves the public form without
     * being rebound, and everything the module wrote lived in the public layout's icon view. Nothing
     * was watching the private header icon, so the notification centre showed the host's bare
     * `smallIcon` for every notification that had arrived while the phone was locked (report:
     * 「又有原生图标出现了」, WeChat + weather + 京东, all posted 34–50 min earlier to a locked
     * phone). Leaving the public form therefore re-runs the normal panel path.
     */
    private fun installHiddenContentHooks() {
        val setHideSensitive = members.rowSetHideSensitive ?: return
        hooks.install(setHideSensitive, "systemui.panel.row.setHideSensitive") { chain ->
            val result = chain.proceed()
            chain.instanceOrNull?.let { row ->
                // The public layout is (re)inflated right after this call, so run once now and
                // again after the next frames — the icon may not exist on the first pass yet.
                applyHiddenContentIcon(row)
                if (!isShowingPublicLayout(row)) {
                    reapplyAfterLeavingPublicForm(row)
                }
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
     * True while the row is rendering its redacted layout. When the host cannot answer, the row is
     * treated as private: writing the theme icon there is the harmless case, keeping a native icon
     * is not.
     */
    private fun isShowingPublicLayout(row: Any): Boolean = runCatching {
        members.rowShouldShowPublic?.invoke(row) as? Boolean
    }.getOrNull() == true

    /**
     * Re-runs the ordinary header path after the row stops showing its public form.
     *
     * Nothing else looks at the row at that moment — the public layout's icon was the one that got
     * the theme icon, and no rebind follows an unlock — so without this the row keeps the host's
     * raw app icon for the rest of the session. Three passes because the host re-inflates the
     * private header asynchronously, exactly like a fresh bind.
     */
    private fun reapplyAfterLeavingPublicForm(row: Any) {
        val view = row as? View ?: return
        val rowWrapper = members.expandableRowGetViewWrapper ?: return
        val snapshot = configuration.snapshot
        reapplyRowIcon(view, rowWrapper)
        view.post { if (configuration.isCurrent(snapshot)) reapplyRowIcon(view, rowWrapper) }
        view.postDelayed(
            { if (configuration.isCurrent(snapshot)) reapplyRowIcon(view, rowWrapper) },
            PANEL_RETRY_DELAY_MILLIS,
        )
        // One line per half minute: the only question this has to answer in a log is whether
        // `setHideSensitive` is the callback ColorOS actually uses for the unlock transition, and
        // if it is not, every row that arrived on the lock screen stays native with no trace at all.
        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.IconReapplied,
            message = "行离开隐藏通知内容形态，按普通路径重铺 header 图标",
            attributes = mapOf(
                "row" to (row.javaClass.simpleName),
                "revision" to snapshot.revision,
            ),
            occurrence = OccurrencePolicy.RateLimited("panel:leave_public_form", 30_000L),
        )
    }

    /**
     * Aggregated groups hand each child row to the container long before it is shown: the host
     * inflates the row while the shade is closed, and opening the shade only lays the existing views
     * out again — `onContentUpdated` never fires for them a second time. Those rows therefore kept
     * the raw `smallIcon` until a SystemUI restart rebuilt the list, which is the "拼多多有几条是原生
     * 图标、重启就又好了" report.
     *
     * `OplusNotificationChildrenContainer.onViewAdded` is the one callback that sees such a row at
     * the moment it joins the group. Apply the icon there, then again on the following frames: the
     * host paints its own small icon right after this returns, and the entry is often still unbound
     * on the very first pass.
     */
    private fun installChildrenContainerHooks() {
        val rowWrapper = members.expandableRowGetViewWrapper
        if (rowWrapper == null) {
            reportChildrenHookMissing(members.childrenContainerOnViewAdded, members.stackOnViewAdded)
            return
        }
        val rowClass = members.expandableRowGetEntry.declaringClass
        // Two entry points, same treatment: a child row joining its group container, and a row
        // joining the shade's own scroll list. Whichever the host goes through, the row is seen
        // before it is first shown, so the icon no longer depends on a bind that may never come.
        val hooksByEntry = listOfNotNull(
            members.childrenContainerOnViewAdded?.let { it to "systemui.panel.children.onViewAdded" },
            members.stackOnViewAdded?.let { it to "systemui.panel.stack.onViewAdded" },
        )
        if (hooksByEntry.isEmpty()) {
            reportChildrenHookMissing(null, null)
            return
        }
        hooksByEntry.forEach { (method, tag) ->
            hooks.install(method, tag) { chain ->
                val result = chain.proceed()
                (chain.args.firstOrNull() as? View)?.let { added ->
                    if (rowClass.isInstance(added)) {
                        scheduleRowIconPasses(added, rowWrapper, configuration.snapshot)
                    }
                }
                result
            }
        }
    }

    private fun scheduleRowIconPasses(row: View, rowWrapper: Method, snapshot: RuntimeSnapshot) {
        CHILD_ROW_REAPPLY_DELAYS.forEach { delay ->
            row.postDelayed(
                {
                    if (configuration.isCurrent(snapshot)) reapplyRowIcon(row, rowWrapper)
                },
                delay,
            )
        }
        // The three timed passes above are all the rows added *while the shade is open* need. A row
        // the host inflates with the shade closed is a different story: it is added before its
        // entry is bound and before it has any size, so all three passes run against a row that is
        // not yet measurable, and nothing ever looks at it again — that is the "拼多多的老消息还是
        // 原生图标，重启 SystemUI 才好" report. So arm a pass on the first frame the row is really
        // drawn: by then it is bound, measured and on screen, which is exactly the host's write
        // window.
        armRowIconOnFirstDraw(row, rowWrapper, snapshot)
    }

    /** Rows that already carry a draw-time pass, so repeated `onViewAdded` calls stay cheap. */
    private val rowDrawArmed = java.util.WeakHashMap<View, Boolean>()

    /**
     * Applies the row's icon on the first frames it is drawn, retrying a bounded number of times.
     *
     * Pre-draw is the one callback that fires only for rows the user can actually see, so a row the
     * host built while the shade was closed gets its icon the moment the shade opens instead of
     * waiting for the next bind — which for an old notification may never come.
     */
    private fun armRowIconOnFirstDraw(row: View, rowWrapper: Method, snapshot: RuntimeSnapshot) {
        // Rows the bind-time hooks already handled (most of them) need no guarding, and skipping
        // them here is what keeps this cheap: a row yielded to a contact photo would otherwise be
        // re-resolved on every attempt.
        if (!rowNeedsDrawPass(row, rowWrapper)) return
        synchronized(rowDrawArmed) {
            if (rowDrawArmed.put(row, true) != null) return
        }
        var attempts = 0
        var lastAttemptAt = 0L
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean = try {
                onRowPreDraw()
            } catch (throwable: Throwable) {
                reportCallbackFailure("panel:row_predraw", throwable)
                true
            }

            private fun onRowPreDraw(): Boolean {
                val observer = row.viewTreeObserver
                val now = SystemClock.uptimeMillis()
                val done = !configuration.isCurrent(snapshot) ||
                    !row.isAttachedToWindow ||
                    attempts >= MAX_ROW_DRAW_ATTEMPTS
                if (!done && (attempts == 0 || now - lastAttemptAt >= ROW_DRAW_RETRY_INTERVAL_MILLIS)) {
                    attempts++
                    lastAttemptAt = now
                    val applied = try {
                        reapplyRowIcon(row, rowWrapper)
                    } catch (exception: Throwable) {
                        diagnostics.runtimeFailure(
                            scope = "panel:row_first_draw",
                            message = "分组子行首次绘制时补铺图标失败",
                            cause = exception,
                            revision = snapshot.revision,
                        )
                        false
                    }
                    if (applied) {
                        forgetRowDrawPass(observer, this, row)
                        return true
                    }
                }
                if (done) forgetRowDrawPass(observer, this, row)
                return true
            }
        }
        row.viewTreeObserver.addOnPreDrawListener(listener)
        // A recycled row keeps its listener until the view is detached; drop it there so the
        // listener never outlives the row it was armed for.
        row.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit

                override fun onViewDetachedFromWindow(view: View) {
                    view.removeOnAttachStateChangeListener(this)
                    forgetRowDrawPass(view.viewTreeObserver, listener, view)
                }
            },
        )
    }

    private fun forgetRowDrawPass(observer: ViewTreeObserver, listener: ViewTreeObserver.OnPreDrawListener, row: View) {
        if (observer.isAlive) observer.removeOnPreDrawListener(listener)
        synchronized(rowDrawArmed) { rowDrawArmed.remove(row) }
    }

    /**
     * Whether a row still needs a draw-time pass.
     *
     * False for rows the bind-time hooks already own (the common case) and for rows whose slot
     * belongs to the host's own contact photo — arming those would only re-resolve an avatar
     * composition on every attempt.
     */
    private fun rowNeedsDrawPass(row: View, rowWrapper: Method): Boolean = runCatching {
        val wrapper = rowWrapper.invoke(row) ?: return@runCatching true
        if (wrapper.iconTarget != PanelIconTarget.Header) return@runCatching false
        if (!wrapper.supportsHeaderIcon()) return@runCatching false
        val icon = members.headerGetIcon?.invoke(wrapper) as? ImageView ?: return@runCatching true
        val shown = icon.drawable ?: return@runCatching true
        val sbn = statusBarNotificationFromRow(row) ?: return@runCatching true
        if (headerIconClaims.isCurrentClaim(icon, sbn.key, shown)) return@runCatching false
        // A sender photo belongs to the host; [HostAvatarPresence] is the same verdict the resolver
        // uses, so this stays in sync with the yield rules.
        if (sbn.notification != null && HostAvatarPresence.evaluate(icon.context, sbn).renderable) {
            return@runCatching false
        }
        true
    }.getOrDefault(false)

    /**
     * `NotificationStackScrollLayout.onLayout`: arms a draw-time pass for every row in the list,
     * including the ones already sitting in a group's children container. Without it a row whose
     * `onViewAdded` fired while the shade was closed would never be looked at again.
     */
    private fun installStackLayoutSweep() {
        val method = members.stackOnLayout ?: return
        val rowWrapper = members.expandableRowGetViewWrapper ?: return
        val rowClass = members.expandableRowGetEntry.declaringClass
        hooks.install(method, "systemui.panel.stack.onLayout") { chain ->
            val result = chain.proceed()
            val stack = chain.instanceOrNull as? View
            if (stack != null) {
                val now = SystemClock.uptimeMillis()
                if (now - lastStackSweepAt >= STACK_SWEEP_INTERVAL_MILLIS) {
                    lastStackSweepAt = now
                    try {
                        sweepRowsForDrawPass(stack, rowClass, rowWrapper)
                    } catch (exception: Throwable) {
                        diagnostics.runtimeFailure(
                            scope = "panel:stack_sweep",
                            message = "遍历通知列表补铺图标失败",
                            cause = exception,
                            revision = configuration.snapshot.revision,
                        )
                    }
                }
            }
            result
        }
    }

    private fun sweepRowsForDrawPass(root: View, rowClass: Class<*>, rowWrapper: Method) {
        val snapshot = configuration.snapshot
        if (!snapshot.config.panelIconReplacementEnabled) return
        val pending = ArrayDeque<View>()
        pending.addLast(root)
        var depth = 0
        var visited = 0
        while (pending.isNotEmpty() && depth <= MAX_SWEEP_DEPTH && visited < MAX_SWEEP_VIEWS) {
            var level = pending.size
            while (level-- > 0 && visited < MAX_SWEEP_VIEWS) {
                val view = pending.removeFirst()
                visited++
                if (rowClass.isInstance(view)) armRowIconOnFirstDraw(view, rowWrapper, snapshot)
                // Keep descending: a group's children container is a child of its own row, so the
                // rows inside it are only reachable *through* the row.
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) pending.addLast(view.getChildAt(index))
                }
            }
            depth++
        }
    }

    @Volatile
    private var lastStackSweepAt = 0L

    /**
     * When this SystemUI process installed the panel hooks.
     *
     * A notification restored after a reboot carries its original (old) `postTime`, but in *this*
     * process the host has not composed anything yet — its first composition still takes a second or
     * two. Treating "old postTime" as "the host already had its chance" would then pin the theme
     * icon over the avatar of the very first message after a reboot, which is the report this whole
     * ladder exists to prevent. So a young process always keeps the full window.
     */
    private val hooksInstalledAt = SystemClock.elapsedRealtime()

    private fun reportChildrenHookMissing(onViewAdded: Method?, stackOnViewAdded: Method?) {
        diagnostics.memberMissing(
            scope = "systemui:panel:children_container",
            message = "未找到行加入回调（容器=$onViewAdded, 列表=$stackOnViewAdded），" +
                "分组内新到达的行只在 bind 时替换图标",
        )
    }

    /**
     * @return true when the row's icon is now the module's (either written by this pass or already
     * owned), so a draw-time caller knows it can stop.
     */
    private fun reapplyRowIcon(row: View, rowWrapper: Method): Boolean {
        if (!row.isAttachedToWindow) return false
        val wrapper = try {
            rowWrapper.invoke(row)
        } catch (exception: Throwable) {
            diagnostics.runtimeFailure(
                scope = "panel:children_row_wrapper",
                message = "分组子行取不到 Header wrapper，跳过图标补铺",
                cause = exception,
                revision = configuration.snapshot.revision,
            )
            return false
        } ?: return false
        // The collapsed summary has its own hooks (initIcon / flushIcon) which already hold a
        // long-lived guard for it; running this pass on top would stack a second one per regroup.
        if (wrapper.iconTarget != PanelIconTarget.Header) return false
        // A custom-view row has no header icon; invoking the header Method on it throws and that
        // exception escapes into onLayout. Same check the caller used, repeated because this is the
        // last unprotected invoke on the draw/layout path.
        if (!wrapper.supportsHeaderIcon()) return false
        applyPanelIcon(
            wrapper = wrapper,
            rowCandidate = row,
            target = PanelIconTarget.Header,
            skipIfAlreadyApplied = true,
        )
        val icon = runCatching { members.headerGetIcon?.invoke(wrapper) as? ImageView }
            .getOrNull() ?: return false
        val shown = icon.drawable ?: return false
        val key = statusBarNotificationFromRow(row)?.key ?: return false
        return headerIconClaims.isCurrentClaim(icon, key, shown)
    }

    /**
     * One record per reason per minute: opening the shade walks dozens of rows, and the only thing
     * this line has to answer is whether a row was ever reached at all — that is the difference
     * between "the hooks never fired for this row type" and "we wrote and the host overwrote us".
     */
    private fun reportRowSkipped(reason: String, detail: String, snapshot: RuntimeSnapshot) {
        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.PanelRowSkipped,
            message = "面板行未替换主题图标：$reason",
            attributes = mapOf("detail" to detail, "revision" to snapshot.revision),
            occurrence = OccurrencePolicy.RateLimited("panel:row_skipped:$reason", 60_000L),
        )
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
        } catch (exception: Throwable) {
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
     * Bridges the host's cold `NotificationIconBadgePackages` observer across boot.
     *
     * Every "avatar + badge" composition is gated on `contains(packageName)`; the gate is answered
     * from the host's in-memory copy of the `systemui_icon_badge_packages` setting, which its
     * observer loads asynchronously. The first bind of a conversation lands inside that window
     * right after a reboot (report: 「重启后 任何人的第一条消息 没有头像 原生图标 第二条恢复」,
     * 2026-11-08 20:29 — the badge hook was never even reached because the gate said false), and
     * the row keeps its raw icon until the next bind.
     *
     * When the original answers false, the same setting is read directly. A package that is in the
     * setting can only have been refused because the observer has not loaded yet — once it has,
     * the original returns true on its own and this bridge goes dormant. Nothing is invented: the
     * package list is the host's own data, the composition that follows is the host's own code.
     */
    private fun installBadgePackagesColdStartBridge() {
        val method = members.badgePackagesContains ?: return
        hooks.install(method, "systemui.panel.badgePackagesColdStart") { chain ->
            val original = chain.proceed()
            if (original == true) return@install original
            val packageName = chain.args.firstOrNull { it is String } as? String
            if (packageName.isNullOrEmpty()) return@install original
            val context = systemUiContext() ?: return@install original
            val allowed = try {
                HostAvatarPackages.contains(context, packageName)
            } catch (throwable: Throwable) {
                diagnostics.runtimeFailure(
                    scope = "panel:badge_packages_bridge",
                    message = "角标诊断：$packageName 读取宿主包列表设置失败，交回原判定",
                    cause = throwable,
                    revision = configuration.snapshot.revision,
                )
                false
            }
            if (!allowed) {
                // Either the package really is not in the host's list, or the setting has not been
                // written yet. Both are answers the module must respect — recorded because a
                // "first message after reboot" report cannot be told apart from a "gate is
                // somewhere else" one without them.
                diagnostics.report(
                    level = DiagnosticLevel.Debug,
                    event = DiagnosticEvent.HostAvatarYield,
                    message = "角标诊断：$packageName 宿主判定不在头像包列表，" +
                        "系统设置里也没有（保持宿主原判定）",
                    attributes = mapOf("package" to packageName),
                    occurrence = OccurrencePolicy.RateLimited(
                        "badge_packages_declined:$packageName",
                        5_000L,
                    ),
                )
                return@install original
            }
            diagnostics.report(
                level = DiagnosticLevel.Info,
                event = DiagnosticEvent.HostAvatarYield,
                message = "角标诊断：$packageName 宿主角标包列表尚未加载完成，按系统设置放行头像合成",
                attributes = mapOf("package" to packageName),
                occurrence = OccurrencePolicy.Once("badge_packages_cold:$packageName"),
            )
            true
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
                    // No prefill here any more: the host composes the avatar picture itself —
                    // mask, badge placement and all — and every local copy of it drew the shape
                    // wrong (「头像形状切割得很奇怪」/「连带角标都被裁剪了」). Handing back `null`
                    // lets the host write its own first frame, then its composed photo.
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
                // branch above never takes it. It falls through to the themed replacement below
                // even when the aggregate ships a real sender photo: the shade rule is "aggregated
                // = themed app icon, standalone = avatar + themed badge". Composing an avatar for
                // it here (the old behaviour) also fought the row's own IconTakeover, which holds
                // the themed plan and re-applied it over the photo on every host repaint.
                if (HostAvatarPresence.isGroupSummary(rowSbn)) {
                    diagnostics.report(
                        level = DiagnosticLevel.Debug,
                        event = DiagnosticEvent.PackageIconResolved,
                        message = "角标诊断：$packageName 聚合摘要按主题图标替换（聚合行不显示头像）",
                        attributes = mapOf(
                            "package" to packageName,
                            "key" to rowSbn.key,
                            "verdict" to verdict.reason,
                        ),
                        occurrence = OccurrencePolicy.Once("header_badge_summary_plain:${rowSbn.key}"),
                    )
                }
                // A row whose notification carries no large icon never gets a host-composed photo
                // (every logged contact-photo verdict had a largeIcon; the rows without one had
                // the host paint the bare app icon), so it belongs to the replacement branch
                // below and reaches the theme icon on the very first bind. When the sender photo
                // exists but loads late, WeChat re-posts the notification and the renderable
                // branch above takes over from that re-bind on.
                if (verdict.reason != HostAvatarPresence.REASON_GROUP_SUMMARY) {
                    // A provisional verdict says "maybe no photo", not "no photo". What must not
                    // survive it is the theme icon this module itself pinned into the host's
                    // badge cache: the host treats a cached entry as a finished composition and
                    // never starts its own avatar pass, so the row would keep that theme icon
                    // even after the sender photo arrives. Drop it and let the host recompose.
                    if (verdict.provisional && themedBadgeRows.get(rowSbn.key) != null) {
                        evictHeaderIconBadge(resolver, chain.instanceOrNull, rawArgument)
                        themedBadgeRows.remove(rowSbn.key)
                        diagnostics.report(
                            level = DiagnosticLevel.Debug,
                            event = DiagnosticEvent.HostAvatarYield,
                            message = "角标诊断：$packageName 头像暂不可判定（${verdict.reason}），" +
                                "清除模块写入的角标缓存交还宿主",
                            attributes = mapOf(
                                "package" to packageName,
                                "key" to rowSbn.key,
                                "verdict" to verdict.reason,
                            ),
                            occurrence = OccurrencePolicy.Once("header_badge_provisional:${rowSbn.key}"),
                        )
                    }
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
            reportGroupSummaryAnchor(iconView, row, sbn, "attachBadge")
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
        } catch (exception: Throwable) {
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
        source: String,
    ) {
        val badge = (row as? View)?.findOplusGroupSummaryIcon()
        val idName = runCatching {
            if (iconView.id == View.NO_ID) "no_id" else iconView.resources.getResourceEntryName(iconView.id)
        }.getOrDefault("unknown")
        // The entry-based paths never hand over the row, so the badge comparison is simply unknown
        // there instead of "missing" — reporting false would read as a failure that never happened.
        val badgeHit = if (row == null) "n/a" else (badge != null).toString()
        val sameInstance = if (row == null) "n/a" else (badge === iconView).toString()
        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.PackageIconResolved,
            message = "聚合徽标定位[$source]：${sbn.packageName} 写入 id=$idName " +
                "size=${iconView.width}x${iconView.height} attached=${iconView.isAttachedToWindow} " +
                "visible=${iconView.visibility == View.VISIBLE} 徽标命中=$badgeHit " +
                "同实例=$sameInstance",
            attributes = mapOf("package" to sbn.packageName, "key" to sbn.key, "source" to source),
            occurrence = OccurrencePolicy.Once("panel:group_anchor:$source:${sbn.key}"),
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
                // Null means the entry is not bound yet, not that the row changed hands; dropping
                // the verification chain there is what left the first painted row unchecked.
                val currentKey = statusBarNotificationFromRow(row)?.key
                if (currentKey != null && currentKey != sbn.key) return@postDelayed
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
            } catch (exception: Throwable) {
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
                    reportGroupSummaryAnchor(iconView, null, sbn, "initIconViewColor")
                    null
                } catch (exception: Throwable) {
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
            } catch (exception: Throwable) {
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
        } catch (exception: Throwable) {
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
     * and the re-apply resolves the same plan the rest of the panel uses (avatar rows yield to the
     * host untouched, everything else gets the themed icon).
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
                } catch (exception: Throwable) {
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
                } catch (exception: Throwable) {
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
            } catch (exception: Throwable) {
                diagnostics.runtimeFailure(
                    scope = "panel:header_color_guard",
                    message = "Oplus Header 二次着色保护失败，交回原实现",
                    cause = exception,
                    revision = snapshot.revision,
                )
                null
            } ?: return@install chain.proceed()
            // Throws when the wrapper is not a header wrapper (custom RemoteViews); that must never
            // escape into the host's updateIconColor.
            val icon = runCatching { getIcon.invoke(wrapper) as? ImageView }.getOrNull()
                ?: return@install chain.proceed()
            val iconIdentifier = try {
                val row = members.notificationViewWrapperRow.get(wrapper)
                val sbn = row?.let(::statusBarNotificationFromRow)
                val drawable = icon.drawable
                if (
                    sbn != null &&
                    drawable != null &&
                    headerIconClaims.isCurrentClaim(icon, sbn.key, drawable)
                ) sbn.key else null
            } catch (exception: Throwable) {
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
                } catch (exception: Throwable) {
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
            val roundnessArgument = chain.args.firstOrNull { it is Boolean } as? Boolean ?: true
            val wrapper = extension?.let { instance ->
                try {
                    oplusHeader.getBase.invoke(instance)
                } catch (exception: Throwable) {
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
            val rowSbn = wrapper?.let(::statusBarNotificationOfWrapper)
            if (wrapper != null && extension != null) {
                // Remembered so a row the host left on its raw icon can be handed back to the same
                // host method later — see [armHostAvatarRetrigger].
                oplusExtensions[wrapper] = OplusExtensionBinding(extension, roundnessArgument)
            }
            rowSbn?.let(headerBadgeRow::set)
            if (rowSbn != null) {
                diagnostics.report(
                    level = DiagnosticLevel.Debug,
                    event = DiagnosticEvent.HostAvatarYield,
                    message = "角标诊断：${rowSbn.packageName} 宿主开始圆角/合成图标" +
                        "（roundness=$roundnessArgument）",
                    attributes = mapOf(
                        "package" to rowSbn.packageName,
                        "key" to rowSbn.key,
                    ),
                    // 15 s, not 1 s: a notification with a live timer re-runs this method every two
                    // seconds for hours, and at one line per second it drowned every other line of
                    // the report the user sends back (2026-11-09 07:20–07:43).
                    occurrence = OccurrencePolicy.RateLimited(
                        "panel:roundness_entry:${rowSbn.packageName}",
                        15_000L,
                    ),
                )
            }
            val result = try {
                chain.proceed()
            } finally {
                headerBadgeRow.remove()
            }
            if (wrapper != null) {
                try {
                    applyPanelIcon(wrapper, target = wrapper.iconTarget)
                } catch (exception: Throwable) {
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

    /** True when the slot holds a picture the host composed, not the app's own icon. */
    private fun isHostComposedIcon(drawable: Drawable?): Boolean =
        drawable != null && drawable.javaClass.name.contains("DrawableWithBadge")

    /**
     * True when the slot already shows something the host stands behind — its "avatar + badge"
     * composition, an avatar, or anything that is not the bare app glyph. Such a row never needs
     * another host pass, and re-running `updateIconRoundness` on it would repaint the raw icon for
     * a frame.
     */
    private fun slotHoldsHostArtwork(icon: ImageView, sbn: StatusBarNotification): Boolean {
        val shown = icon.drawable ?: return false
        if (isHostComposedIcon(shown)) return true
        return !runCatching { looksLikeRawIcon(icon.context, sbn, shown) }.getOrDefault(false)
    }

    /**
     * Re-runs the host's own avatar pass for a row it left on the bare app icon.
     *
     * After a reboot the host registers `NotificationIconBadgePackages` (the observer behind
     * `Settings.Global systemui_icon_badge_packages`) *asynchronously*, and
     * `updateIconRoundness` gives up entirely when `contains(package)` says false. The first
     * message of every conversation then keeps the raw icon, while the second one — a few seconds
     * later, once the list has loaded — composes normally (reports 2026-11-08 20:29 and 21:01).
     *
     * Rather than predicting when the observer is ready, the row is re-offered to
     * `updateIconRoundness` a few times: that method is the host's own entry into "avatar + badge"
     * (it re-checks the package list, looks the composition up in its cache, schedules the async
     * pass, applies and caches the result). The module never draws anything here — if the host
     * declines, the row keeps exactly what the stock build would show.
     *
     * The ladder stops as soon as the slot holds a host composition, and never stacks: the key is
     * claimed before the first beat and only released by the last one (or by an answer).
     */
    private fun armHostAvatarRetrigger(
        rowView: View,
        icon: ImageView,
        wrapper: Any,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
    ) {
        if (slotHoldsHostArtwork(icon, sbn)) return
        val key = sbn.key
        if (avatarRetriggers.putIfAbsent(key, snapshot.revision) != null) return
        HOST_AVATAR_RETRIGGER_MILLIS.forEachIndexed { index, delay ->
            val last = index == HOST_AVATAR_RETRIGGER_MILLIS.lastIndex
            rowView.postDelayed(retrigger@{
                try {
                    if (!configuration.isCurrent(snapshot)) return@retrigger
                    if (!icon.isAttachedToWindow) return@retrigger
                    val current = runCatching { statusBarNotificationFromRow(rowView) }.getOrNull()
                        ?: return@retrigger
                    if (current.key != key) return@retrigger
                    // Only a slot that still shows the bare glyph is worth another host pass:
                    // re-running the method writes the raw icon first, so asking on a row that
                    // already has its picture would flash it away for a frame.
                    if (slotHoldsHostArtwork(icon, sbn)) {
                        avatarRetriggers.remove(key)
                        return@retrigger
                    }
                    val binding = oplusExtensions[wrapper]
                    val update = members.oplusHeader?.updateIconRoundness
                    if (binding == null || update == null) {
                        if (last) {
                            avatarRetriggers.remove(key)
                            diagnostics.report(
                                level = DiagnosticLevel.Warning,
                                event = DiagnosticEvent.MemberMissing,
                                message = "角标诊断：${sbn.packageName} 找不到宿主 header 扩展，" +
                                    "无法让宿主重试头像合成",
                                attributes = mapOf("package" to sbn.packageName, "key" to key),
                                occurrence = OccurrencePolicy.Once("panel:avatar_retrigger_missing:$key"),
                            )
                        }
                        return@retrigger
                    }
                    // Re-running the host method only helps while its own gate is the blocker.
                    // On 2026-11-09 07:24 it was not: the first WeChat message of the day kept the
                    // bare green glyph for five and a half minutes, all four beats returned
                    // `roundness=false` and changed nothing, and the avatar only appeared when a
                    // later rebind re-ran the host's composition. So from the second beat on the
                    // picture is built with the host's own composer instead of asking again: the
                    // sender photo comes from the notification, the badge is the themed app icon —
                    // the same two arguments SystemUI passes itself, every pixel of geometry still
                    // the host's. A miss writes nothing at all, so the row keeps exactly what the
                    // stock build shows.
                    if (index >= HOST_AVATAR_COMPOSE_BEAT) {
                        val previous = icon.drawable
                        val composed = composeHeaderAvatar(icon, wrapper, current, snapshot)
                        if (composed != null) {
                            icon.clearColorFilter()
                            icon.imageTintList = null
                            icon.setImageDrawable(composed)
                            diagnostics.report(
                                level = DiagnosticLevel.Info,
                                event = DiagnosticEvent.HostAvatarReplace,
                                message = "角标诊断：${sbn.packageName} 宿主几拍都没合成头像，" +
                                    "改用宿主自己的合成函数补上（头像+主题角标）",
                                attributes = mapOf(
                                    "package" to sbn.packageName,
                                    "key" to key,
                                    "attempt" to (index + 1).toString(),
                                ),
                                occurrence = OccurrencePolicy.Once(
                                    "panel:avatar_retrigger_compose:$key",
                                ),
                            )
                            // The composition is the module's write, not the host's, so it needs the
                            // same guard the themed path installs: a sibling arriving or a shade
                            // re-layout makes the host repaint the row from its own cache, which puts
                            // the bare glyph back. The guard recognises this very instance and holds
                            // it, and stands down for anything the host composes itself.
                            headerPlaceholderGuards.put(
                                icon,
                                HeaderPlaceholderGuard(
                                    icon = icon,
                                    row = rowView,
                                    sbn = current,
                                    snapshot = snapshot,
                                    plan = NotificationIconResolver.PanelIconRenderPlan(
                                        composed,
                                        null,
                                    ),
                                    hostArtwork = previous,
                                ),
                            )?.stop()
                            headerPlaceholderGuards[icon]?.start()
                            avatarRetriggers.remove(key)
                            return@retrigger
                        }
                    }
                    diagnostics.report(
                        level = DiagnosticLevel.Info,
                        event = DiagnosticEvent.HostAvatarYield,
                        message = "角标诊断：${sbn.packageName} 头像位仍是原样图标，" +
                            "第 ${index + 1} 次请宿主重跑合成（roundness=${binding.roundness}）",
                        attributes = mapOf(
                            "package" to sbn.packageName,
                            "key" to key,
                            "attempt" to (index + 1).toString(),
                        ),
                        occurrence = OccurrencePolicy.Once("panel:avatar_retrigger:$key:$index"),
                    )
                    update.invoke(binding.extension, binding.roundness)
                    if (last) avatarRetriggers.remove(key)
                } catch (throwable: Throwable) {
                    reportCallbackFailure("panel:avatar_retrigger", throwable)
                }
            }, delay)
        }
    }

    private fun applyPanelIcon(
        wrapper: Any,
        rowCandidate: Any? = null,
        target: PanelIconTarget = PanelIconTarget.Header,
        expectedSnapshot: RuntimeSnapshot? = null,
        allowDeferredLookup: Boolean = true,
        verifyAfterWrite: Boolean = true,
        skipIfAlreadyApplied: Boolean = false,
    ) {
        val snapshot = expectedSnapshot ?: configuration.snapshot
        if (expectedSnapshot != null && !configuration.isCurrent(snapshot)) return
        if (!snapshot.config.panelIconReplacementEnabled) return
        // Custom-view notifications have no header icon at all. Bailing out here keeps the failure
        // out of the log (it used to be reported as an "injection failed" error on every layout)
        // and off the layout path. Collapsed-group summaries are exempt: they look their icon up
        // through the same Method but fall back to the row's own container, and that lookup runs
        // inside the try below.
        if (target == PanelIconTarget.Header && !wrapper.supportsHeaderIcon()) return
        try {
            val row = rowCandidate
                ?: runCatching { members.notificationViewWrapperRow.get(wrapper) }.getOrNull()
                ?: run {
                    reportRowSkipped("no_row", wrapper.javaClass.simpleName, snapshot)
                    return
                }
            val rowView = row as? View
            val icon = when (target) {
                PanelIconTarget.Header -> members.headerGetIcon?.invoke(wrapper) as? ImageView
                // GroupIconManager colors NotificationHeaderViewWrapper.mIcon (CachingIconView).
                PanelIconTarget.OplusGroupSummary ->
                    (members.headerGetIcon?.invoke(wrapper) as? ImageView)
                        ?: rowView?.findOplusGroupSummaryIcon()
            } ?: run {
                // Rows built by the host while the shade was closed can still be mid-inflate here:
                // there is no header icon view yet on the first pass, and nothing else would ever
                // come back for them. Retry once on the next loop for both targets.
                if (allowDeferredLookup && rowView != null) {
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
                } else {
                    reportRowSkipped("no_icon", "${wrapper.javaClass.simpleName}/${row.javaClass.simpleName}", snapshot)
                }
                return
            }
            val sbn = statusBarNotificationFromRow(row) ?: run {
                reportRowSkipped("no_sbn", row.javaClass.simpleName, snapshot)
                return
            }
            if (target == PanelIconTarget.Header) {
                // A row that already shows the icon this module put there needs no second write: the
                // add-time passes fire on every regroup, and re-writing for the same key only costs a
                // redraw and a log line.
                val shown = icon.drawable
                if (skipIfAlreadyApplied && shown != null &&
                    headerIconClaims.isCurrentClaim(icon, sbn.key, shown)
                ) {
                    return
                }
                headerIconClaims.release(icon)
            }
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
                if (target == PanelIconTarget.Header &&
                    snapshot.resolver.shouldKeepNativePanelAvatar(sbn)
                ) {
                    // The host owns the whole personal-message row: sender lookup, avatar cache,
                    // mask, asynchronous rebind and late photo. A guard left by an older bind must
                    // not paint over it. The app icon is themed upstream in
                    // getPackageIconForNotification, so the native composition gets the themed
                    // corner badge without this class writing the ImageView at all.
                    //
                    // "Personal message" is a row *type*, not a promise that a photo exists. Right
                    // after a reboot WeChat posts the first message of the day from a cold process,
                    // often with no `largeIcon` at all and no re-post for another ten seconds
                    // (2026-11-10 16:11: the row answered `no-large-icon` and sat on the bare green
                    // glyph until 16:11:34, when an update finally carried the photo). Handing
                    // *that* row to the host buys nothing but the stock app icon — the one thing
                    // this module exists to replace. So the yield only happens when the
                    // notification actually carries artwork; without it the row falls through to
                    // the ordinary branch below, which paints the themed icon at once and gives the
                    // slot back to the host the moment it composes a photo.
                    val hostAvatar = HostAvatarPresence.evaluate(icon.context, sbn)
                    if (hostAvatar.renderable || hostAvatar.provisional) {
                        headerPlaceholderGuards.remove(icon)?.stop()
                        diagnostics.report(
                            level = DiagnosticLevel.Debug,
                            event = DiagnosticEvent.HostAvatarYield,
                            message = "角标诊断：${sbn.packageName} 个人消息交还 SystemUI 原生头像逻辑" +
                                "，仅替换原生角标",
                            attributes = mapOf(
                                "package" to sbn.packageName,
                                "key" to sbn.key,
                                "verdict" to hostAvatar.reason,
                                "provisional" to hostAvatar.provisional.toString(),
                            ),
                            occurrence = OccurrencePolicy.Once("panel:native_avatar:${sbn.key}"),
                        )
                        // Right after a reboot the host's own pass can come back empty-handed: its
                        // `NotificationIconBadgePackages` observer (data:
                        // `Settings.Global systemui_icon_badge_packages`) is registered late, and
                        // `contains(pkg)` answering false skips the whole composition — every first
                        // message of a conversation stays on the raw app icon until the app posts
                        // again (reports 2026-11-08 20:29 / 21:01, where the *second* message of the
                        // same conversation composed normally). The host is asked again on a ladder
                        // and, from the second beat on, the picture is composed with the host's own
                        // composer — see [armHostAvatarRetrigger].
                        rowView?.let { armHostAvatarRetrigger(it, icon, wrapper, sbn, snapshot) }
                        return
                    }
                    // A personal-message row with nothing to compose from would keep the bare app
                    // icon for as long as the notification lives, and that is the one picture the
                    // module must not leave on screen. Fall through to the ordinary branch below:
                    // it paints the themed icon now, installs the guard, and hands the slot back to
                    // the host as soon as a photo shows up in a later post.
                    diagnostics.report(
                        level = DiagnosticLevel.Debug,
                        event = DiagnosticEvent.HostAvatarYield,
                        message = "角标诊断：${sbn.packageName} 个人消息里没有头像素材" +
                            "（${hostAvatar.reason}），按规则铺主题图标，宿主画出头像再让路",
                        attributes = mapOf(
                            "package" to sbn.packageName,
                            "key" to sbn.key,
                            "verdict" to hostAvatar.reason,
                        ),
                        occurrence = OccurrencePolicy.Once("panel:avatar_missing:${sbn.key}"),
                    )
                }
                // Yielding to the host avatar. ColorOS composes that avatar asynchronously on its
                // own dispatcher, so the row paints the plain small icon (or an "app icon + badge"
                // composition) for a moment first. That intermediate is the host's, and the module
                // deliberately does not pre-draw anything here: the host bakes the avatar mask and
                // the corner badge into one picture, and every local copy of it came out with the
                // wrong outline (「头像形状切割的很奇怪」, 「连带角标都被裁剪了」).
                if (target == PanelIconTarget.Header) {
                    // An earlier bind of this very icon may have reached the opposite conclusion
                    // ("no avatar in this notification") and installed the themed-icon guard. That
                    // guard is bound to the previous `StatusBarNotification` and survives the
                    // rebind, so it kept repainting the theme icon over the avatar that arrived
                    // with the re-post — the row stayed on the theme icon for the whole session
                    // (report: 「第一条消息直接变成了主题图标 没有头像」, WeChat posts the message
                    // first and the sender photo ~9s later). Once the row yields the slot belongs
                    // to the host; the ladder below re-arms a guard if the host still draws nothing.
                    val verdict = HostAvatarPresence.evaluate(icon.context, sbn)
                    if (verdict.renderable || verdict.provisional) {
                        headerPlaceholderGuards.remove(icon)?.stop()
                    }
                    // A row with **nothing to compose from** does not wait at all. The ladder below
                    // is armed on this `CachedIconView`, and on the lock screen the host rebuilds
                    // those rows: the deferred passes then run against a view nobody displays, or
                    // are dropped by the `isAttachedToWindow` / `isCurrent` guards, and the row keeps
                    // the bare glyph for as long as the notification lives. The 15:00 report is
                    // exactly that — `先让宿主画几拍再决定` at 15:00:38.509, then *no* demotion at all,
                    // while the screenshot at 15:00:44 still shows the green WeChat icon; the avatar
                    // only arrived at 15:01:47 when the app finally re-posted with the photo.
                    //
                    // So the themed icon goes in now and the slot is watched afterwards: the guard
                    // hands the row straight back to the host the moment it composes a picture, and
                    // [armAvatarUndoRecheck] re-asks the notification once the app has had time to
                    // attach a photo. A photo that is already there keeps the old behaviour — see
                    // [guardHeaderIfHostSkipsAvatar] — because there the wait can still pay off.
                    if (!verdict.renderable) {
                        val immediate = snapshot.resolver.resolvePanelIconPlan(
                            context = icon.context,
                            sbn = sbn,
                            originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
                            ignoreHostAvatar = true,
                        )
                        if (immediate != null) {
                            val previous = icon.drawable
                            icon.applyRenderPlan(immediate, PanelIconTarget.Header)
                            icon.drawable?.let { drawable ->
                                headerIconClaims.claim(icon, sbn.key, drawable)
                            }
                            guardHeaderIconAgainstHostPlaceholder(
                                icon = icon,
                                row = row,
                                sbn = sbn,
                                snapshot = snapshot,
                                plan = immediate,
                                hostArtwork = previous,
                            )
                            armAvatarUndoRecheck(icon, row, sbn, snapshot)
                            diagnostics.report(
                                level = DiagnosticLevel.Debug,
                                event = DiagnosticEvent.HostAvatarReplace,
                                message = "角标诊断：${sbn.packageName} 通知里没有可用的头像" +
                                    "（${verdict.reason}），直接铺主题图标，宿主画出头像再让路",
                                attributes = mapOf(
                                    "package" to sbn.packageName,
                                    "key" to sbn.key,
                                    "verdict" to verdict.reason,
                                    "provisional" to verdict.provisional.toString(),
                                ),
                                occurrence = OccurrencePolicy.Once(
                                    "panel:immediate_theme:${sbn.key}",
                                ),
                            )
                            // Nothing left to wait for: the slot holds the themed icon and the
                            // guard owns it from here. Arming the ladder as well would only let it
                            // mistake that write for a host composition.
                            return
                        }
                    }
                    // Everything that is left here either carries a photo (so the host still has a
                    // chance to compose it) or could not be resolved to a themed icon at all. The
                    // ladder watches the slot in the first case and acts as the cold-start net in
                    // the second: it resolves the theme icon lazily and forces redraws until the
                    // engine is warm, then takes the slot from the app-icon placeholder the host
                    // painted in the meantime.
                    //
                    // [canRenderAvatar] is a *prediction*; when it says "photo" the guard installs
                    // late instead of not at all, so a host that never paints the photo cannot
                    // leave the row on its raw icon.
                    guardHeaderIfHostSkipsAvatar(icon, row, sbn, snapshot, wrapper)
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

            // The artwork the host had in the slot before this write is exactly what it puts back
            // when it repaints the row from its own cache (a new notification in the group, a
            // collapse from group to single row, a shade re-layout). Holding on to it lets the
            // header guard recognise that repaint by content instead of guessing what it is.
            val hostArtwork = if (target == PanelIconTarget.Header) icon.drawable else null
            icon.applyRenderPlan(plan, target)
            if (target == PanelIconTarget.OplusGroupSummary) {
                reportGroupSummaryAnchor(icon, row, sbn, "panelIcon")
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
                if (target == PanelIconTarget.Header) {
                    // A new notification in the same group makes the host re-lay out every sibling
                    // row and repaint their icons from its own cache, without calling back into any
                    // of the hooks above. The timed ladder that [verifyIconAfterWrite] used to be is
                    // long over by then, so those rows dropped back to the raw app icon until the
                    // next bind — the "拼多多来了新消息，旧消息的图标变回原生" report. The guard
                    // holds the plan for as long as the row lives and only steps in when what is on
                    // screen is measurably the host's own placeholder, so a late contact photo still
                    // wins.
                    guardHeaderIconAgainstHostPlaceholder(icon, row, sbn, snapshot, plan, hostArtwork)
                }
            }
        } catch (exception: Throwable) {
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
     * `applyPanelIcon` 直接返回、把头像槽交给宿主，不存在本模块写下的 drawable 需要补铺。
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
                // See verifyGroupSummaryIconAfterWrite: an unbound entry must not end the chain.
                val currentKey = statusBarNotificationFromRow(row)?.key
                if (currentKey != null && currentKey != sbn.key) return@postDelayed
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
            } catch (exception: Throwable) {
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
        // Rows answer null until the host binds the entry; that must not stop the guard, so the
        // provider is allowed to return null and is re-asked on every frame.
        keepGroupSummaryIcon(icon, sbn, snapshot, reapply, currentSbn = {
            runCatching { row?.let { statusBarNotificationFromRow(it) } }.getOrNull()
        })
    }

    private fun keepGroupSummaryIcon(
        icon: ImageView,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
        reapply: () -> Unit,
        currentSbn: () -> StatusBarNotification?,
    ) {
        groupIconTakeovers.put(icon, IconTakeover(icon, sbn, snapshot, currentSbn, reapply))?.stop()
        groupIconTakeovers[icon]?.start()
    }

    private inner class IconTakeover(
        private val icon: ImageView,
        private val sbn: StatusBarNotification,
        private val snapshot: RuntimeSnapshot,
        /** Re-read on every frame: the row may not be bound yet, and a null answer is not an exit. */
        private val currentSbn: () -> StatusBarNotification?,
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

        /**
         * Replaces this takeover with one bound to [latest] instead of dropping it.
         *
         * A configuration reload swaps the `RuntimeSnapshot` instance, so `isCurrent` turns false
         * for every guard installed under the previous one — while the host's `GroupIconManager`
         * coroutine keeps repainting the collapsed row. Stopping there handed the badge back to the
         * raw app icon for the rest of the session; only a SystemUI restart (fresh rows, fresh
         * guards) brought the theme icon back, which is the "first notification of a session keeps
         * the original icon" report. A null plan under the new configuration means the row should
         * yield now, so dropping the takeover is correct in that case.
         */
        fun rearmUnder(latest: RuntimeSnapshot) {
            stop()
            val latestSbn = runCatching { currentSbn() }.getOrNull() ?: return
            if (latestSbn.key != sbn.key) return
            val plan = runCatching {
                latest.resolver.resolveGroupSummaryIconPlan(
                    context = icon.context,
                    sbn = latestSbn,
                    originalSmallIcon = latestSbn.originalSmallIcon(diagnostics, latest.revision),
                )
            }.getOrNull() ?: return
            writeGroupSummaryPlan(icon, plan)
            keepGroupSummaryIcon(icon, latestSbn, latest, { writeGroupSummaryPlan(icon, plan) }, currentSbn)
            reportGroupSummaryAnchor(icon, null, latestSbn, "snapshotReload")
        }

        override fun onPreDraw(): Boolean = try {
            onGroupPreDraw()
        } catch (throwable: Throwable) {
            reportCallbackFailure("panel:group_icon_predraw", throwable)
            true
        }

        private fun onGroupPreDraw(): Boolean {
            if (stopped) return true
            if (!icon.isAttachedToWindow) {
                stop()
                return true
            }
            if (!configuration.isCurrent(snapshot)) {
                rearmUnder(configuration.snapshot)
                return true
            }
            val key = runCatching { currentSbn()?.key }.getOrNull()
            // A freshly inflated row answers null here for its first frames: the host binds the entry
            // asynchronously, so [statusBarNotificationFromRow] finds nothing yet. Reading that as
            // "the row now shows something else" stopped the guard before the row had ever drawn,
            // and the host's raw app icon then stayed on screen until SystemUI restarted — the "two
            // notifications from one app, one replaced and one original, both correct after a
            // restart" report. Only a *different* key means the row was genuinely reused.
            if (key == null) return true
            if (key != sbn.key) {
                stop()
                return true
            }
            val expected = applied
            val current = icon.drawable
            // `applied == null` means this guard was created before the host had put any artwork in
            // the view — the collapsed-group badge is inflated 0x0 while the shade is closed, which
            // is exactly when notifications arrive. Stopping there handed the row back to the host
            // for the rest of the session: the badge was painted with the raw app icon once the
            // shade laid it out, and only the *next* notification update re-ran the injection —
            // the "first notification after a restart keeps the original icon, the next one is
            // fine" report. Adopt whatever is in the slot and only react to later swaps.
            if (current === expected) return true
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
        plan: NotificationIconResolver.PanelIconRenderPlan? = null,
        /** What the host had painted before the themed write; its repaint is this again. */
        hostArtwork: Drawable? = null,
        /**
         * Skip the "the host will paint a photo here" prediction. Only the caller that has already
         * looked at the slot and found the raw icon in it may pass `true`: there the prediction is
         * known to be wrong, and honouring it would leave the row unguarded for good.
         */
        force: Boolean = false,
    ) {
        val context = icon.context
        if (!force && HostAvatarPresence.evaluate(context, sbn).renderable) return
        // No themed lookup here on purpose: the engine is cold, that is why this path is running
        // at all. The guard resolves the themed icon lazily on each draw instead.
        headerPlaceholderGuards
            .put(icon, HeaderPlaceholderGuard(icon, row, sbn, snapshot, plan, hostArtwork))
            ?.stop()
        headerPlaceholderGuards[icon]?.start()
    }

    /**
     * Whether ColorOS could still be composing this row's avatar right now.
     *
     * True while the notification is new (the app may re-post with the photo, WeChat takes ~9 s) or
     * while this process is young (a cold SystemUI has to start the app before it can read anything).
     * False means the host has had its chance: whatever is in the slot is its final answer, so the
     * row may be demoted as soon as the first pass confirms it still holds a bare glyph.
     */
    private fun isFreshForHost(sbn: StatusBarNotification): Boolean {
        val sincePost = System.currentTimeMillis() - sbn.postTime
        if (sincePost in 0..HOST_FRESH_POST_MILLIS) return true
        return SystemClock.elapsedRealtime() - hooksInstalledAt < HOST_FRESH_POST_MILLIS
    }

    /**
     * Arms the themed-icon guard after actually looking at the slot, instead of trusting the
     * prediction that the host will paint a contact photo there.
     *
     * The prediction ([HostAvatarPresence.canRenderAvatar]) reads the notification — a large icon
     * that is not the launcher icon, a `Person`, a messaging style — and concludes the host has a
     * photo to compose. ColorOS does not always follow through: WeChat's rows in the shade stayed
     * on the plain green `smallIcon` for the whole session while every log line said `contact-photo`,
     * because the module had yielded *and* skipped installing a guard (the first statement of
     * [guardHeaderIconAgainstHostPlaceholder] returns on exactly that verdict). Nothing was
     * watching the slot, so nothing could repair it — the report "微信的图标没有被替换".
     *
     * The host composes its avatar asynchronously (a coroutine on `Dispatchers.Default`), and it
     * repaints the slot with the bare glyph between compositions, so "the slot holds the raw icon"
     * is only meaningful when the host has *never* produced a composition for this row. The ladder
     * in [HOST_AVATAR_GRACE_MILLIS] therefore does three things in order: remember every composition
     * it sees, restore that composition when the host repaints over it, and only arm the themed-icon
     * guard when ten seconds pass without a single composition — or, for a notification the host has
     * already had minutes to answer, after [SETTLED_AVATAR_LADDER_MILLIS].
     */
    private fun guardHeaderIfHostSkipsAvatar(
        icon: ImageView,
        row: Any?,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
        /** The view wrapper this icon belongs to; only used to size the host's own composer. */
        wrapper: Any? = null,
    ) {
        val verdict = HostAvatarPresence.evaluate(icon.context, sbn)
        if (!verdict.renderable && !verdict.provisional) {
            guardHeaderIconAgainstHostPlaceholder(icon, row, sbn, snapshot)
            return
        }
        // Both cases are answered by watching the slot, but they differ in how much time the host
        // deserves. "It has a photo" is worth ten seconds: ColorOS composes asynchronously and the
        // app may still be loading the picture. "It probably has no photo" (a launcher-icon
        // placeholder, an undecodable icon, a missing one) is a *guess* that the slot can settle
        // within a few seconds, and sitting on the raw app icon that long is its own complaint.
        //
        // The exception is a package ColorOS itself treats as a personal-avatar sender. There the
        // guess is usually wrong — WeChat posts many notifications without a `largeIcon` and the
        // host composes the sender photo from its own contact store, just late. A themed icon
        // strips both the avatar and the badge, so those rows get the full window; meanwhile they
        // show the host's own "app icon + badge", which is a fine intermediate.
        val ladder = when {
            verdict.renderable && !verdict.provisional -> HOST_AVATAR_GRACE_MILLIS
            HostAvatarPackages.contains(icon.context, sbn.packageName) -> HOST_AVATAR_GRACE_MILLIS
            else -> PLACEHOLDER_PROBE_MILLIS
        }.let { full ->
            // The long window only buys anything while the host may still be working on this post.
            // For a row that was posted minutes ago in a process that has been up for hours, the
            // host's answer either already landed or never will: waiting the full ten seconds only
            // leaves the bare app icon on screen after the user pulls the shade down.
            if (isFreshForHost(sbn)) full else SETTLED_AVATAR_LADDER_MILLIS
        }
        // What the host managed to compose for this row during the window. ColorOS repaints the
        // slot with the bare glyph between its own compositions (a rebind, a regroup, a sibling
        // arriving), so one bare-glyph sample proves nothing: a sample taken 2.4s after the last
        // composition is a repaint, not "the host cannot compose here".
        //
        // On 2026-11-05 (right after a reboot) every first WeChat message hit exactly that: the
        // breakpoint log shows pass 0 yielding on `DrawableWithBadge 200x200` and pass 2 — three
        // seconds later — deciding "宿主没画" and forcing the theme icon, so the row that *had* a
        // photo was left with no avatar at all.
        var hostArtwork: Drawable? = null
        // The picture this module built with the host's own composer, when ColorOS declined to
        // build one itself. Kept so a later pass does not compose the same row again.
        var composedArtwork: Drawable? = null
        // What the notification itself says, refreshed on every pass. The verdict computed at bind
        // time is stale the moment the app re-posts with a picture attached, and the log line that
        // records a demotion has to name what the last pass actually saw.
        var currentReason = verdict.reason
        // When the ladder was armed. Posted on the view, so the system may freeze it for minutes
        // (screen off, doze) and fire a late pass against a slot that has been rebound since —
        // see the overrun check inside the lambda.
        val armedAt = SystemClock.elapsedRealtime()
        if (!verdict.renderable) {
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.HostAvatarYield,
                message = "角标诊断：${sbn.packageName} 头像位判定为占位（${verdict.reason}），" +
                    "先让宿主画几拍再决定",
                attributes = mapOf(
                    "package" to sbn.packageName,
                    "key" to sbn.key,
                    "verdict" to verdict.reason,
                    "difference" to verdict.difference.toString(),
                ),
                occurrence = OccurrencePolicy.Once("host_avatar_probe:${sbn.key}"),
            )
        }
        ladder.forEachIndexed { index, delay ->
            icon.postDelayed({
                // Anything escaping this lambda lands in SystemUI's main Handler, so it gets the
                // same net as the draw callbacks. The body keeps its own early returns.
                try {
                if (!configuration.isCurrent(snapshot) || !icon.isAttachedToWindow) return@postDelayed
                // A pass that arrives far outside the window it was armed for is not evidence of
                // anything: the row was rebound, the panel slept, and whatever sits in the slot now
                // belongs to a different draw. The 21:37/21:39 report is exactly this — pass 0
                // yielded on the host's own composition, pass 4 ran two minutes later against a
                // rebound slot and forced the themed icon over a row that had just shown a photo.
                if (SystemClock.elapsedRealtime() - armedAt >
                    ladder.last() + LADDER_OVERRUN_MILLIS
                ) {
                    return@postDelayed
                }
                // A guard already holding this slot means an earlier pass decided the avatar never
                // came; it re-checks on every draw, so this ladder is done.
                if (headerPlaceholderGuards[icon] != null) return@postDelayed
                // The row may have been re-used for another notification while we waited.
                val latest = runCatching { row?.let { statusBarNotificationFromRow(it) } }
                    .getOrNull()
                if (latest != null && latest.key != sbn.key) return@postDelayed
                // Re-read the notification on every pass instead of trusting the copy captured at
                // bind time. WeChat attaches the sender photo by **re-posting the same key**, and
                // SystemUI can update the entry without rebinding the row — so a verdict computed at
                // bind time keeps saying `no-large-icon` for the whole window while the photo has
                // been sitting in the notification for seconds, and the last pass demotes a row that
                // would otherwise have shown 「头像 + 角标」 (report: 「护理官这个通知从原生跳成
                // 主题图标 没加载出来个人头像」). Evaluating is cheap: the pixel comparison behind it
                // is cached per `key#postTime`, and a notification without a large icon answers from
                // its extras alone.
                val observed = latest ?: sbn
                val reread = HostAvatarPresence.evaluate(icon.context, observed)
                currentReason = reread.reason
                val carriesPhoto = reread.renderable && !reread.provisional
                // A photo in the notification is **not** the same thing as a composition in the
                // slot, and treating the former as an answer is what left rows on the bare icon for
                // a whole session (report: 「重启后首条又是原生图标」, 2026-11-08 11:03 — WeChat
                // re-posted the same key with a `largeIcon` at 11:05, this branch read
                // `contact-photo`, every pass returned here, and the row never got an avatar
                // because the host never composed one for it). Note the photo and carry on: only
                // the slot can say whether ColorOS actually painted something.
                if (carriesPhoto) {
                    diagnostics.report(
                        level = DiagnosticLevel.Debug,
                        event = DiagnosticEvent.HostAvatarYield,
                        message = "角标诊断：${sbn.packageName} 重新读到头像（${reread.reason}），" +
                            "继续观察宿主是否合成",
                        attributes = mapOf(
                            "package" to sbn.packageName,
                            "key" to sbn.key,
                            "pass" to index.toString(),
                            "verdict" to reread.reason,
                            "difference" to reread.difference.toString(),
                        ),
                        occurrence = OccurrencePolicy.Once("host_avatar_late:${sbn.key}"),
                    )
                }
                val current = icon.drawable ?: return@postDelayed
                // Whether the slot already holds a layered picture. Only used to decide whether
                // pass 0 has anything left to wait for — it must not shorten the window: see the
                // demote gate below.
                val hostAnswered = HostAvatarPresence.hasHostCompositionShape(current)
                if (!looksLikeRawIcon(icon.context, sbn, current)) {
                    hostArtwork = current
                    diagnostics.report(
                        level = DiagnosticLevel.Debug,
                        event = DiagnosticEvent.HostAvatarYield,
                        message = "角标诊断：${sbn.packageName} 头像位里已有宿主合成结果，让给宿主",
                        attributes = mapOf(
                            "package" to sbn.packageName,
                            "key" to sbn.key,
                            "pass" to index.toString(),
                            "art" to "${current.javaClass.simpleName} " +
                                "${current.intrinsicWidth}x${current.intrinsicHeight}",
                        ),
                        occurrence = OccurrencePolicy.Once("host_avatar_landed:${sbn.key}"),
                    )
                    return@postDelayed
                }
                // The host already proved it composes an avatar for this row, and this sample is
                // one of its own repaints. Put that composition back instead of demoting the row to
                // the theme icon: the picture is still correct, only the write order slipped.
                val landed = hostArtwork
                if (landed != null) {
                    icon.clearColorFilter()
                    icon.imageTintList = null
                    icon.setImageDrawable(landed)
                    diagnostics.report(
                        level = DiagnosticLevel.Info,
                        event = DiagnosticEvent.HostAvatarReplace,
                        message = "角标诊断：${sbn.packageName} 宿主重绘回原样图标，" +
                            "恢复它刚合成的头像",
                        attributes = mapOf(
                            "package" to sbn.packageName,
                            "key" to sbn.key,
                            "pass" to index.toString(),
                        ),
                        occurrence = OccurrencePolicy.Once("host_avatar_restored:${sbn.key}"),
                    )
                    return@postDelayed
                }
                // Give the host the first sample to itself. On a cold start it is still starting the
                // app's process to read the photo, and working on this pass would take the main
                // thread away from the very work we are waiting for. A composition that already
                // landed is the exception: there is nothing left to wait for on this pass.
                if (index == 0 && !hostAnswered) return@postDelayed
                // ColorOS only composes "avatar + badge" for senders it classifies as people. A
                // personal WeChat message that it does not recognise keeps the bare green glyph for
                // the whole session: in `_diag_20261108_1118` the notification held a real
                // `largeIcon` (`contact-photo`, difference 80.78) while the slot stayed on a 32x32
                // `BitmapDrawable` from 11:05 to 11:18, and the host's badge cache answered null the
                // one time it was asked (report: 「这是个人微信啊 我肯定要头像啊」).
                //
                // So do the one thing the host did not, using **its own** composer: hand
                // `createBackgroundWithAppIcon` the sender photo and the themed app icon as the
                // badge. Geometry — squash mask, badge size, badge placement — stays entirely with
                // the host, which is the whole reason this is allowed at all after the local-copy
                // attempts (「头像形状切割得很奇怪」/「连带角标都被裁剪了」).
                if (carriesPhoto && composedArtwork == null) {
                    val composed = composeHeaderAvatar(icon, wrapper, observed, snapshot)
                    if (composed != null) {
                        composedArtwork = composed
                        icon.clearColorFilter()
                        icon.imageTintList = null
                        icon.setImageDrawable(composed)
                        diagnostics.report(
                            level = DiagnosticLevel.Info,
                            event = DiagnosticEvent.HostAvatarReplace,
                            message = "角标诊断：${sbn.packageName} 宿主没合成头像，" +
                                "改用宿主自己的合成函数补上（头像+主题角标）",
                            attributes = mapOf(
                                "package" to sbn.packageName,
                                "key" to sbn.key,
                                "pass" to index.toString(),
                                "verdict" to currentReason,
                            ),
                            occurrence = OccurrencePolicy.Once("host_avatar_composed:${sbn.key}"),
                        )
                        // Guard the picture: the host repaints a row from its own cache whenever a
                        // sibling arrives, the shade re-lays out or the entry updates, and that
                        // repaint puts the bare glyph back. The guard writes this composition again
                        // for exactly those repaints, and stands down as soon as the slot holds the
                        // host's own "avatar + badge" artwork.
                        headerPlaceholderGuards.put(
                            icon,
                            HeaderPlaceholderGuard(
                                icon = icon,
                                row = row,
                                sbn = sbn,
                                snapshot = snapshot,
                                plan = NotificationIconResolver.PanelIconRenderPlan(composed, null),
                                hostArtwork = current,
                            ),
                        )?.stop()
                        headerPlaceholderGuards[icon]?.start()
                        return@postDelayed
                    }
                    // Nothing to compose from: no theme icon for the badge, no decodable photo, or
                    // a build that no longer exposes the host's composer. Say so once, so a log can
                    // separate "the helper vanished" from "the row really has no photo".
                    diagnostics.report(
                        level = DiagnosticLevel.Debug,
                        event = DiagnosticEvent.HostAvatarYield,
                        message = "角标诊断：${sbn.packageName} 无法用宿主合成函数补头像" +
                            "（缺少主题图标/头像素材或接口不可用），继续等待",
                        attributes = mapOf(
                            "package" to sbn.packageName,
                            "key" to sbn.key,
                            "pass" to index.toString(),
                            "verdict" to currentReason,
                        ),
                        occurrence = OccurrencePolicy.Once("host_avatar_compose_miss:${sbn.key}"),
                    )
                }
                // When this row may give up and take the themed icon. The wait is *not* the same
                // for the two kinds of row, because only one of them still has something to gain
                // from waiting:
                //
                // * The notification carries no photo at all (`provisional` — WeChat posted it from
                //   a cold process, before the sender picture existed). Nothing in the extras can be
                //   composed into an avatar, so the only thing the long window buys is a *re-post*
                //   with a photo, and a re-post rebinds the row on its own. Meanwhile the row sits
                //   on the bare glyph for eighteen seconds — which is exactly what the user sees as
                //   「重启后 原生图标 … 重启 systemui 后 原生图标」: the 13:02 report showed the same
                //   WeChat aggregate painted raw on every cold SystemUI start and only turning
                //   themed 18 s later. It may therefore demote at the first pass past
                //   [PROVISIONAL_DEMOTE_AFTER_MILLIS].
                // * The notification *does* carry a photo, ColorOS simply has not composed it yet.
                //   There the wait is still worth something: [composeHeaderAvatar] retries on every
                //   pass with the host's own composer, and a cold theme engine answers null for the
                //   first seconds after a restart. Those rows keep the full window and only give up
                //   on the last pass.
                //
                // Demotion is no longer irreversible either, which is what makes the early gate
                // safe: the guard installed below hands the slot back the moment the host paints a
                // composition, and [AVATAR_DEMOTE_RECHECK_MILLIS] re-asks the notification in case
                // the photo arrives without a rebind.
                // The gate reads [carriesPhoto] — what *this* pass just saw — rather than the verdict
                // captured at bind time: a re-post that lands mid-ladder must not be demoted on the
                // strength of a stale "no photo" answer.
                val mayDemote = if (carriesPhoto) {
                    index >= ladder.lastIndex
                } else {
                    delay >= PROVISIONAL_DEMOTE_AFTER_MILLIS
                }
                if (!mayDemote) return@postDelayed
                diagnostics.report(
                    level = DiagnosticLevel.Info,
                    event = DiagnosticEvent.HostAvatarReplace,
                    message = "角标诊断：${sbn.packageName} 等了 ${delay}ms 头像位仍是原样图标" +
                        "（${currentReason}），改用主题图标",
                    attributes = mapOf(
                        "package" to sbn.packageName,
                        "key" to sbn.key,
                        "pass" to index.toString(),
                        // What the slot actually held when the ladder gave up. Naming it is what
                        // tells a "the host never composed" row apart from a composition the
                        // appearance checks failed to recognise.
                        "art" to "${current.javaClass.simpleName} " +
                            "${current.intrinsicWidth}x${current.intrinsicHeight}",
                    ),
                    occurrence = OccurrencePolicy.Once("host_avatar_skipped:${sbn.key}"),
                )
                guardHeaderIconAgainstHostPlaceholder(
                    icon = icon,
                    row = row,
                    sbn = sbn,
                    snapshot = snapshot,
                    force = true,
                )
                // Giving up is not final any more. The guard watches the *slot*, so it can hand the
                // row back once the host composes an avatar — but it cannot see a photo that only
                // arrives in the notification after this window closed, and the app's re-post is not
                // bounded by our ladder. Ask the notification once more after a longer silence and
                // undo the demotion if the picture showed up: leaving the row on the theme icon for
                // the rest of the session is exactly the failure users describe as 「没加载出来个人
                // 头像」. One extra evaluation on a cached verdict, so the cost is a no-op for a row
                // that really has no avatar.
                armAvatarUndoRecheck(icon, row, sbn, snapshot)
                } catch (throwable: Throwable) {
                    reportCallbackFailure("panel:avatar_probe", throwable)
                }
            }, delay)
        }
    }

    /**
     * Asks the row's notification once more, later, whether a sender photo showed up.
     *
     * Both places that put the themed icon on a row without waiting — the immediate write for a
     * notification that carries no photo, and the ladder's demotion — need the same way back. The
     * guard can only react to what the host *paints*; a photo that arrives in the notification
     * without a rebind and without a composition would otherwise leave the row themed for the rest
     * of the session, which users report as 「没加载出来个人头像」.
     */
    private fun armAvatarUndoRecheck(
        icon: ImageView,
        row: Any?,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
    ) {
        val rowView = row as? View ?: return
        val rowWrapper = members.expandableRowGetViewWrapper ?: return
        rowView.postDelayed(avatarUndo@{
            try {
                if (!configuration.isCurrent(snapshot)) return@avatarUndo
                if (!icon.isAttachedToWindow) return@avatarUndo
                // A later pass already took the slot back; nothing to undo.
                if (headerPlaceholderGuards[icon] == null) return@avatarUndo
                val nowSbn = runCatching {
                    statusBarNotificationFromRow(rowView)
                }.getOrNull() ?: return@avatarUndo
                if (nowSbn.key != sbn.key) return@avatarUndo
                val again = HostAvatarPresence.evaluate(icon.context, nowSbn)
                if (!again.renderable || again.provisional) return@avatarUndo
                headerPlaceholderGuards.remove(icon)?.stop()
                diagnostics.report(
                    level = DiagnosticLevel.Info,
                    event = DiagnosticEvent.HostAvatarReplace,
                    message = "角标诊断：${sbn.packageName} 降级后通知补上了头像" +
                        "（${again.reason}），撤销主题图标交回宿主",
                    attributes = mapOf(
                        "package" to sbn.packageName,
                        "key" to sbn.key,
                        "verdict" to again.reason,
                        "difference" to again.difference.toString(),
                    ),
                    occurrence = OccurrencePolicy.Once("host_avatar_undo:${sbn.key}"),
                )
                reapplyRowIcon(rowView, rowWrapper)
            } catch (throwable: Throwable) {
                reportCallbackFailure("panel:avatar_undo", throwable)
            }
        }, AVATAR_DEMOTE_RECHECK_MILLIS)
    }

    /**
     * Asks the resolver for the host's own "avatar + badge" picture for this row.
     *
     * Null whenever anything is missing — no selector match, no theme icon, no sender photo, or a
     * build without the host's composer — and the caller then keeps its existing fallback (the
     * themed icon). Never draws the picture here: the resolver hands the arguments to
     * `createBackgroundWithAppIcon`.
     */
    private fun composeHeaderAvatar(
        icon: ImageView,
        wrapper: Any?,
        sbn: StatusBarNotification,
        snapshot: RuntimeSnapshot,
    ): Drawable? = runCatching {
        val size = headerContentSizePx(wrapper, icon)
        lastHeaderContentSizePx = size
        snapshot.resolver.composeAvatarWithHost(
            context = icon.context,
            sbn = sbn,
            contentSizePx = size,
        )
    }.getOrNull()

    /**
     * `resolveHeaderIconContentSizePx()` is the very value the host boxes into its composer; the
     * laid-out width is only a fallback for builds that renamed it.
     *
     * A freshly bound row is not measured yet, so both can come back as zero. Fall back to the size
     * the last composition used, and then to the density-derived default, so the composer always
     * has a box.
     */
    private fun headerContentSizePx(wrapper: Any?, icon: ImageView): Int {
        if (wrapper != null) {
            resolveContentSizeMethod(wrapper)?.let { method ->
                runCatching { method.invoke(wrapper) as Int }.getOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { return it }
            }
        }
        return when {
            icon.width > 0 -> icon.width
            lastHeaderContentSizePx > 0 -> lastHeaderContentSizePx
            else -> (icon.resources.displayMetrics.density * FALLBACK_CONTENT_SIZE_DP).toInt()
        }
    }

    /**
     * Resolved once **on success**. A miss is not cached: the first caller can be a different
     * wrapper type (a group summary container rather than the header wrapper), and remembering that
     * failure would size every later composition from the density default.
     */
    private fun resolveContentSizeMethod(wrapper: Any): Method? {
        contentSizeMethod?.let { return it }
        return runCatching {
            wrapper.javaClass.getMethod("resolveHeaderIconContentSizePx").also {
                contentSizeMethod = it
            }
        }.getOrNull()
    }

    /**
     * Whether the slot still holds artwork the host paints when it has no avatar to compose: the
     * notification's `smallIcon` (the tinted brand glyph ColorOS refills `mIcon` with), the
     * launcher icon, a blank sheet or the monochrome mask.
     *
     * Deliberately an allow-list of "no photo here": anything unrecognised is treated as a real
     * composition, because painting over a contact photo is the one mistake this module must not
     * make.
     */
    private fun looksLikeRawIcon(
        context: android.content.Context,
        sbn: StatusBarNotification,
        drawable: Drawable,
    ): Boolean =
        // Checked first and on its own: the host's "avatar + badge" picture shares the centre of
        // the artwork with the bare glyph it replaces, so every content comparison below can match
        // it and the caller then overwrites a real avatar (see looksLikeHostComposition).
        !HostAvatarPresence.looksLikeHostComposition(drawable, context, sbn) &&
            (HostAvatarPresence.looksLikeNotificationSmallIcon(context, sbn, drawable) ||
                HostAvatarPresence.looksLikeLauncherIcon(context, sbn.packageName, drawable) ||
                HostAvatarPresence.looksBlank(drawable) ||
                HostAvatarPresence.looksLikeMonochromeMask(drawable))

    /**
     * Last-resort net for callbacks SystemUI invokes on its main thread.
     *
     * An `onPreDraw` / `onLayout` body that throws is not logged and skipped: the exception
     * propagates into SystemUI's own traversal and kills the process, and the notification that
     * caused it is still there after the restart — the 09:49 crash loop. `Throwable` rather than
     * `Exception` on purpose: the artwork checks allocate bitmaps, and an `OutOfMemoryError`
     * escaping one of these callbacks is just as fatal.
     */
    private fun reportCallbackFailure(scope: String, throwable: Throwable) {
        runCatching {
            diagnostics.runtimeFailure(
                scope = scope,
                message = "SystemUI 主线程回调异常已拦截，避免进程重启",
                cause = throwable,
                revision = configuration.snapshot.revision,
            )
        }
    }

    private inner class HeaderPlaceholderGuard(
        private val icon: ImageView,
        private val row: Any?,
        private val sbn: StatusBarNotification,
        private val snapshot: RuntimeSnapshot,
        /**
         * The plan this row was replaced with, when there was one. Holding the plan is what lets
         * the guard outlive the bind: the host re-paints every row of a group whenever a sibling
         * notification arrives, and without a guard the rows that were already replaced fall back
         * to the raw app icon until the next bind (report: "新消息来了以后旧消息的图标变回原生了").
         * A null plan keeps the old cold-start behaviour and resolves the theme icon lazily.
         */
        private val plan: NotificationIconResolver.PanelIconRenderPlan? = null,
        /**
         * The artwork the host had in the slot when the themed icon was written. Its cache repaint
         * puts exactly this back, and it is usually neither the launcher icon nor a plain mask —
         * ColorOS refills `mIcon` from `Notification#smallIcon` — so recognising it by content is
         * what keeps the guard alive through that repaint. See [HostAvatarPresence.looksLikeSameArtwork].
         */
        private val hostArtwork: Drawable? = null,
    ) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {

        private var observer: ViewTreeObserver? = null
        private var writes = 0
        private var lastWriteAt = 0L
        private var stopped = false

        /** Resolved on first success; the theme engine can be cold for the first seconds. */
        private var themed: Drawable? = plan?.drawable

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

        /**
         * Swaps this guard for one bound to [latest] instead of dropping it.
         *
         * A configuration reload (theme switch, settings change) swaps the `RuntimeSnapshot`
         * instance, so `isCurrent` turns false for every guard installed under the previous one
         * while the rows keep living. Stopping there handed those rows back to the host for the
         * rest of the session — only a SystemUI restart brought the theme icon back. Re-resolving
         * under the new snapshot is the correct exit; a null plan there means the row should
         * yield now, and then dropping the guard is right.
         */
        fun rearmUnder(latest: RuntimeSnapshot) {
            stop()
            val latestSbn = runCatching { row?.let { statusBarNotificationFromRow(it) } }.getOrNull()
                ?: return
            if (latestSbn.key != sbn.key) return
            val latestPlan = runCatching {
                latest.resolver.resolvePanelIconPlan(
                    context = icon.context,
                    sbn = latestSbn,
                    originalSmallIcon = latestSbn.originalSmallIcon(diagnostics, latest.revision),
                )
            }.getOrNull() ?: return
            val previous = icon.drawable
            icon.applyRenderPlan(latestPlan, PanelIconTarget.Header)
            headerPlaceholderGuards
                .put(icon, HeaderPlaceholderGuard(icon, row, latestSbn, latest, latestPlan, previous))
                ?.stop()
            headerPlaceholderGuards[icon]?.start()
        }

        override fun onPreDraw(): Boolean = try {
            onHeaderPreDraw()
        } catch (throwable: Throwable) {
            reportCallbackFailure("panel:header_guard_predraw", throwable)
            true
        }

        private fun onHeaderPreDraw(): Boolean {
            if (stopped) return true
            // A detached view never draws; if this does fire while the row is between layouts just
            // wait — [onViewAttachedToWindow] re-arms the listener anyway.
            if (!icon.isAttachedToWindow) return true
            // A reloaded configuration swaps the snapshot instance, so every guard from the
            // previous one is stale. Dropping it left the row to the host for the rest of the
            // session (only a restart brought the theme icon back), so re-arm instead.
            if (!configuration.isCurrent(snapshot)) {
                rearmUnder(configuration.snapshot)
                return true
            }
            val key = runCatching { row?.let { statusBarNotificationFromRow(it) }?.key }.getOrNull()
            // A freshly inflated row answers null here for its first frames: the host binds the entry
            // asynchronously, so [statusBarNotificationFromRow] finds nothing yet. Reading that as
            // "the row now shows something else" stopped the guard before the row had ever drawn,
            // and the host's raw app icon then stayed on screen until SystemUI restarted — the "two
            // notifications from one app, one replaced and one original, both correct after a
            // restart" report. Only a *different* key means the row was genuinely reused.
            if (key == null) return true
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
            // A "avatar + badge" picture this guard wrote itself (built with the host's composer
            // from the notification's photo) is not the host taking the slot back: hold it, or the
            // host's next cache repaint — a sibling arriving, a shade re-layout — drops the row
            // straight back to the bare glyph.
            if (plan?.drawable != null && current === plan.drawable) return true
            val now = SystemClock.uptimeMillis()
            // Rate-limit first: identifying the artwork samples two bitmaps, and a host that keeps
            // repainting every frame must not turn that into per-frame work.
            if (writes >= MAX_GROUP_ICON_TAKEOVERS &&
                now - lastWriteAt < TAKEOVER_QUIET_INTERVAL_MILLIS
            ) {
                return true
            }
            // Anything else on screen is either the host's own placeholder or real content (a
            // contact photo, or the "photo + badge" composition it built around one). Only the
            // former may be covered; the latter wins and the guard is done.
            //
            // Exception: an aggregated row is themed *by rule*, even when the host composed a
            // genuine "photo + badge" for it. Yielding there handed the row back to the host's
            // composition and the two pictures alternated on every repaint, so for a summary the
            // guard keeps re-applying the themed plan exactly like the group IconTakeover does.
            if (!HostAvatarPresence.isGroupSummary(sbn) && !isHostPlaceholder(current)) {
                reportYieldedSlot(current)
                stop()
                return true
            }
            writes++
            lastWriteAt = now
            if (writes == 1) {
                diagnostics.report(
                    level = DiagnosticLevel.Info,
                    event = DiagnosticEvent.HostAvatarReplace,
                    message = "角标诊断：${sbn.packageName} 宿主的头像位里是原样图标（应用图标/小图标），改用主题图标",
                    attributes = mapOf("package" to sbn.packageName, "key" to sbn.key),
                    occurrence = OccurrencePolicy.Once("header_placeholder_guard:${sbn.key}"),
                )
            }
            if (plan != null) {
                icon.applyRenderPlan(plan, PanelIconTarget.Header)
            } else {
                icon.clearColorFilter()
                icon.imageTintList = null
                icon.setImageDrawable(themedNow)
            }
            return true
        }

        /**
         * Whether what the host (re)painted into the slot is its own placeholder rather than a
         * picture worth keeping.
         *
         * The three appearance checks cover the usual artwork — the app's launcher icon, a blank
         * sheet, the monochrome mask — but not the refill ColorOS actually does most often: `mIcon`
         * gets `Notification#smallIcon` again, a tinted brand glyph that matches none of them.
         * Comparing against [hostArtwork] catches that by content, so a plain single row cannot
         * silently drop back to the raw icon and stay there (the guard used to stop on the first
         * unknown drawable, i.e. on exactly that refill).
         */
        private fun isHostPlaceholder(drawable: Drawable): Boolean =
            !HostAvatarPresence.looksLikeHostComposition(drawable, icon.context, sbn) &&
                (isHostAppIconPlaceholder(drawable) ||
                    HostAvatarPresence.looksLikeSameArtwork(drawable, hostArtwork) ||
                    HostAvatarPresence.looksLikeNotificationSmallIcon(icon.context, sbn, drawable))

        private fun isHostAppIconPlaceholder(drawable: Drawable): Boolean =
            HostAvatarPresence.looksLikeLauncherIcon(icon.context, sbn.packageName, drawable) ||
                HostAvatarPresence.looksBlank(drawable) ||
                HostAvatarPresence.looksLikeMonochromeMask(drawable)

        /** Records what was left on screen when the guard gave the slot back, so a log explains it. */
        private fun reportYieldedSlot(drawable: Drawable) {
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.HostAvatarYield,
                message = "角标诊断：${sbn.packageName} 头像位里不是宿主的占位图，让给宿主",
                attributes = mapOf(
                    "package" to sbn.packageName,
                    "key" to sbn.key,
                    "art" to "${drawable.javaClass.simpleName} " +
                        "${drawable.intrinsicWidth}x${drawable.intrinsicHeight}",
                ),
                occurrence = OccurrencePolicy.Once("header_guard_yield:${sbn.key}"),
            )
        }

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
        override fun onPreDraw(): Boolean = try {
            onSummaryPreDraw()
        } catch (throwable: Throwable) {
            reportCallbackFailure("panel:summary_blank_predraw", throwable)
            true
        }

        private fun onSummaryPreDraw(): Boolean {
            if (stopped) return true
            if (!icon.isAttachedToWindow) {
                stop()
                return true
            }
            if (!configuration.isCurrent(snapshot)) {
                // Same reload problem as IconTakeover.rearmUnder: this guard and the refill ladder it
                // started both key off the old snapshot instance, so a reload killed the only net
                // that catches a collapsed row the host painted blank. Re-install under the new one.
                stop()
                guardSummaryIconAgainstBlank(icon, row, sbn, configuration.snapshot)
                return true
            }
            val key = runCatching { row?.let { statusBarNotificationFromRow(it) }?.key }.getOrNull()
            // A freshly inflated row answers null here for its first frames: the host binds the entry
            // asynchronously, so [statusBarNotificationFromRow] finds nothing yet. Reading that as
            // "the row now shows something else" stopped the guard before the row had ever drawn,
            // and the host's raw app icon then stayed on screen until SystemUI restarted — the "two
            // notifications from one app, one replaced and one original, both correct after a
            // restart" report. Only a *different* key means the row was genuinely reused.
            if (key == null) return true
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
            reportGroupSummaryAnchor(iconView, null, sbn, "entryIconDrawable")
            // This is the view the collapsed row actually paints. `attachCollapsedGroupBadge` hands
            // over a different instance (0x0 and detached while the shade is closed), so the guard
            // installed over there watches a view nobody draws: `GroupIconManager` repainted this
            // one with the raw app icon and nothing took it back until the next notification update
            // re-ran the injection — the "first notification after a restart shows the original
            // icon, the next one is fine" report.
            keepGroupSummaryIcon(
                icon = iconView,
                sbn = sbn,
                snapshot = snapshot,
                reapply = { writeGroupSummaryPlan(iconView, plan) },
                currentSbn = {
                    runCatching {
                        members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification
                    }.getOrNull()
                },
            )
        } catch (exception: Throwable) {
            diagnostics.runtimeFailure(
                scope = "panel:replace_icon:${PanelIconTarget.OplusGroupSummary.diagnosticName}",
                message = "通知面板规则图标注入失败，保留 ColorOS 原结果",
                cause = exception,
                revision = snapshot.revision,
            )
        }
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

    /**
     * Whether this row wrapper actually declares the header icon.
     *
     * `row.getRowWrapper()` returns whichever wrapper the entry picked, and a notification with a
     * custom `RemoteViews` gets a `NotificationCustomViewWrapper` — no header, no `mIcon`.
     * [members.headerGetIcon] is a `Method` declared on `NotificationHeaderViewWrapper`, so invoking
     * it on anything else throws `IllegalArgumentException: Expected receiver of type ...`
     * ("got NotificationCustomViewWrapper"). On 2026-11-05 that exception ran up through
     * `NotificationStackScrollLayout.onLayout` and took SystemUI down in a restart loop (four new
     * pids in sixteen seconds). Every call site that asks a wrapper for its header icon must check
     * this first. When the method itself could not be resolved nothing is blocked, so an unknown
     * build keeps the old behaviour.
     */
    private fun Any.supportsHeaderIcon(): Boolean {
        val method = members.headerGetIcon ?: return true
        return method.declaringClass.isInstance(this)
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
         * How long to let ColorOS finish composing "avatar + badge" before deciding it never will.
         *
         * The composition runs in a coroutine on `Dispatchers.Default`; on a warm SystemUI the
         * landings are well inside a second, so checking earlier would race it and flash the theme
         * icon on every avatar row. A cold SystemUI is a different story: the first message after a
         * reboot has to start the app's process (or read its contacts cache), and the host repaints
         * the slot with the bare glyph in between. Three seconds was not enough — the passes ran out
         * while the row was between two of the host's own compositions, and the fallback pinned the
         * theme icon on a notification that did have a photo.
         *
         * Seven passes over eighteen seconds: the early ones only observe (and remember whatever
         * the host composed), and the very last one is the only place allowed to give up.
         *
         * Ten seconds used to be the whole window, on the theory that WeChat re-posts a message
         * about nine seconds after the sender photo is written. It is a *typical* delay, not a
         * bound: a 服务号 push had its `largeIcon` one second after the post, while a 微信小店/
         * 客服 message sat at `no-large-icon` for the full ten seconds and was demoted to the theme
         * icon while its photo was still loading (report: 「护理官这个通知从原生跳成主题图标
         * 没加载出来个人头像」). The extra passes cost nothing when the host answers early — the
         * ladder returns on the first pass that finds a composition — so the window only matters
         * for rows that really have nothing.
         */
        val HOST_AVATAR_GRACE_MILLIS = longArrayOf(
            700L,
            1600L,
            3000L,
            6000L,
            10_000L,
            14_000L,
            18_000L,
        )

        /**
         * The same ladder for a notification the host has already had its chance at.
         *
         * Ten seconds exist for the sequence right after a post: WeChat writes the message first
         * and the sender photo up to nine seconds later, and a cold SystemUI still has to start the
         * app's process. None of that is pending for a row that was posted minutes ago — if ColorOS
         * were going to compose an avatar for it, it did so the first time the row was shown. Waiting
         * the full window there is what made a notification centre pulled down at 07:50 still show
         * the bare app icons of notifications from 07:00 (report: 「又有原生图标出现了」, three rows,
         * all posted while the phone was locked).
         *
         * Six seconds, not the 2.5 s this used to be: the row being *visible* is itself the trigger
         * for the host's composition, and the first time the shade is pulled down the row may only
         * now enter the window. Giving up at 2.5 s demoted a row whose avatar the host was still
         * working on — the failure users see as 「从原生跳成主题图标 没加载出来个人头像」.
         */
        val SETTLED_AVATAR_LADDER_MILLIS = longArrayOf(400L, 1200L, 2500L, 4000L, 6000L)

        /**
         * When to ask the host to run its own avatar pass again on a row it left on the raw icon.
         *
         * `NotificationIconBadgePackages` — the gate `updateIconRoundness` opens with — is an
         * observer over `Settings.Global systemui_icon_badge_packages`, registered asynchronously.
         * Right after a reboot it is still empty, so the first message of every conversation gets
         * no composition at all, while the second one (tens of seconds later) composes fine. The
         * measured landings: post at +45 s drew nothing, post at +64 s composed.
         *
         * The beats therefore straddle that window instead of guessing one moment: 2 s, 5 s, 10 s,
         * 20 s and 40 s after the row yielded. Each is a single reflection call into the host's own
         * method — cheap, idempotent, and powerless if the host still refuses. The first beats were
         * moved earlier (were 4/12/25/40) so that a row the host can already compose stops showing
         * the stock icon sooner.
         */
        val HOST_AVATAR_RETRIGGER_MILLIS = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L, 40_000L)

        /**
         * First beat of [HOST_AVATAR_RETRIGGER_MILLIS] that also composes the picture itself.
         *
         * The first beat is reserved for the host: on the platform these rows exist for, the
         * host's own asynchronous pass is usually in flight by then and wins legitimately. From the
         * second beat on (5 s) a row that still shows the bare glyph is not "waiting for the
         * observer any more" — the host has answered twice and produced nothing (2026-11-09
         * 07:24: four beats, five and a half minutes of the raw WeChat icon), so the host's own
         * composer is used with the notification's photo and the themed app icon.
         */
        const val HOST_AVATAR_COMPOSE_BEAT = 1

        /**
         * Header icon box used when neither the host's own resolver nor the layout has an answer —
         * 40dp is the size the ColorOS 17 headers compose their avatars into at every density.
         */
        const val FALLBACK_CONTENT_SIZE_DP = 40f

        /**
         * How recently a notification must have arrived — or this process must have started — for
         * the full grace window to apply. Covers WeChat's re-post (about 9 s) plus slack.
         */
        const val HOST_FRESH_POST_MILLIS = 12_000L

        /**
         * How long after a demotion to look at the notification one last time.
         *
         * The ladder bounds how long the host gets *before* the row is replaced, but not how long
         * the app may take to attach the sender photo: a 微信 服务号/客服 message was still at
         * `no-large-icon` after ten seconds and got the theme icon while its picture was loading
         * (report: 「护理官这个通知从原生跳成主题图标 没加载出来个人头像」). The re-check costs one
         * cached verdict read and only ever *removes* a guard, so a late photo is picked up without
         * making the common case any slower.
         */
        const val AVATAR_DEMOTE_RECHECK_MILLIS = 15_000L

        /**
         * When a row whose notification carries **no** photo may stop waiting for the host.
         *
         * Such a row has nothing to compose from: no `largeIcon`, no `Person`, no messaging style.
         * The host answers it with the bare glyph and keeps answering that until the app re-posts
         * with the sender picture — and a re-post rebinds the row anyway. Holding the full
         * eighteen-second window on that guess is what put the raw icon on screen after every
         * reboot and every SystemUI restart (report: 「重启后 原生图标 锁屏解锁后主题图标 重启
         * systemui 后 原生图标」, all three screenshots were this one WeChat aggregate waiting out
         * the ladder).
         *
         * 1600 ms is the second pass: every host composition ever logged landed on pass 0 (700 ms),
         * so a row ColorOS really does paint an avatar for still shows that picture without a
         * themed icon flashing first, while a row it ignores turns themed one beat later instead of
         * eighteen seconds later. The guard and the re-check below keep both outcomes reversible.
         */
        const val PROVISIONAL_DEMOTE_AFTER_MILLIS = 1_600L

        /**
         * How late a pass may arrive and still count as evidence about the slot.
         *
         * The ladder is a chain of `View#postDelayed` callbacks, and the platform is free to hold
         * them: screen off, doze, a frozen SystemUI. The last pass of a cold-start reboot run was
         * observed firing **two minutes** after it was armed, against a slot that had been rebound
         * in the meantime, and it forced the themed icon over a row that had just shown an avatar.
         * Beyond this overdue margin the pass is dropped instead of acted on.
         */
        const val LADDER_OVERRUN_MILLIS = 5_000L

        /**
         * How long a *provisional* "no avatar" verdict may sit on the slot before the row is
         * demoted to the themed icon.
         *
         * The verdict comes from artwork that looks like the app's own icon, from an icon that
         * would not decode, or from a missing one — all three are what a cold-started app ships
         * before the sender photo exists, and ColorOS can still paint a real photo for that row
         * from its own store (the first WeChat message after a reboot is exactly that case, and the
         * original report was "每个联系人的第一条消息都是主题图标，没有头像"). Unlike the ten-second
         * ladder this one cannot linger: if the guess was right, the row is sitting on the raw app
         * icon the whole time, which is the "why did my icon stop being themed" complaint.
         */
        val PLACEHOLDER_PROBE_MILLIS = longArrayOf(700L, 1600L, 3000L)

        /**
         * When a child row joins an aggregated group. The first pass runs on the next loop so the
         * host's own inflation has finished, the later ones cover the host painting its small icon
         * and the entry being bound late. Delays are absolute, not cumulative.
         */
        val CHILD_ROW_REAPPLY_DELAYS = longArrayOf(0L, 320L, 1000L)

        /**
         * Draw-time retries for a row the host inflated while the shade was closed. The row only
         * becomes bound and measurable once it is on screen, and the host may still be finishing
         * its own bind on the first frames, so a handful of spaced attempts is enough.
         */
        const val MAX_ROW_DRAW_ATTEMPTS = 8
        const val ROW_DRAW_RETRY_INTERVAL_MILLIS = 300L

        /** `onLayout` fires on every expand/collapse; one sweep per half second is plenty. */
        const val STACK_SWEEP_INTERVAL_MILLIS = 500L

        /** The list → row → children container chain, nothing deeper. */
        const val MAX_SWEEP_DEPTH = 3
        const val MAX_SWEEP_VIEWS = 240

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
    }
}
