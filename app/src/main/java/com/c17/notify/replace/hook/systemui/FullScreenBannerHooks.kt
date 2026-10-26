package com.c17.notify.replace.hook.systemui

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.ImageView
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.hook.HookRegistrar
import com.c17.notify.replace.hook.icon.NotificationIconResolver
import com.c17.notify.replace.hook.runtimeFailure

/**
 * ColorOS "notification light feedback while fullscreen" uses FullScreenBanner, not IconManager.
 * Icon replacement follows the notification-panel plan (same drawable + tint rules).
 */
internal class FullScreenBannerHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val configuration: SystemUiConfiguration,
    private val members: FullScreenBannerMembers,
) {
    private companion object {
        /** Host wrapper that paints "main artwork + corner badge"; see [rebadgeWithTheme]. */
        const val DRAWABLE_WITH_BADGE_CLASS =
            "com.oplus.systemui.notification.utlils.DrawableWithBadge"
    }

    @Volatile
    private var lastEntry: Any? = null

    fun install() {
        installShowBannerHook()
        installBuildNotificationHook()
        installUpdateBannerHook()
        installSetIconHook()
        installBackgroundIconHook()
    }

    /**
     * Capture the entry before ColorOS builds the first banner frame. Without this, the initial
     * setIcon call can run before buildNotification and keep the default mono-white icon.
     */
    private fun installShowBannerHook() {
        val method = members.showBanner ?: return
        hooks.install(method, "systemui.fullscreen_banner.showBanner") { chain ->
            val entry = chain.args.getOrNull(0)
            if (entry != null) lastEntry = entry
            val result = chain.proceed()
            val helper = chain.instanceOrNull
            val context = members.helperContext?.get(helper) as? Context
            val banner = members.helperFullScreenBanner?.get(helper)
            if (context != null && entry != null && banner != null) {
                refreshBannerIcon(context, banner, entry)
            }
            result
        }
    }

    private fun installBuildNotificationHook() {
        val method = members.buildNotification ?: return
        hooks.install(method, "systemui.fullscreen_banner.buildNotification") { chain ->
            val entry = chain.args.getOrNull(1)
            if (entry != null) lastEntry = entry
            val builder = chain.proceed()
            val context = chain.args.getOrNull(0) as? Context
                ?: members.helperContext?.get(chain.instanceOrNull) as? Context
            if (builder != null && context != null && entry != null) {
                patchBuilderIcon(context, builder, entry)
            }
            builder
        }
    }

    private fun installUpdateBannerHook() {
        val method = members.updateBanner ?: return
        hooks.install(method, "systemui.fullscreen_banner.updateBanner") { chain ->
            val banner = chain.instanceOrNull ?: return@install chain.proceed()
            val builder = chain.args.getOrNull(0)
            val context = bannerContext(banner)
            val entry = resolveEntry(banner)
            val plan = if (builder != null && context != null && entry != null) {
                patchBuilderIcon(context, builder, entry)
            } else {
                null
            }
            val result = chain.proceed()
            if (plan != null) {
                schedulePanelRenderPlan(banner, plan)
            } else if (context != null && entry != null) {
                refreshBannerIcon(context, banner, entry)
            }
            result
        }
    }

    private fun installSetIconHook() {
        val method = members.bannerSetIcon ?: return
        hooks.install(method, "systemui.fullscreen_banner.setIcon") { chain ->
            val banner = chain.instanceOrNull ?: return@install chain.proceed()
            val context = bannerContext(banner)
            val entry = resolveEntry(banner)
            val plan = if (context != null && entry != null) {
                resolvePanelPlan(context, entry)
            } else {
                null
            }
            // Chain.getArgs() is immutable; build a fresh argument array when a plan applies.
            val newArgs = when {
                plan != null -> chain.args.toTypedArray().also {
                    it[0] = plan.drawable
                    val avatar = chain.args.getOrNull(2) as? Drawable
                    if (avatar != null) {
                        it[2] = plan.drawable.constantState?.newDrawable()?.mutate() ?: plan.drawable
                    }
                    // ColorOS 17: largeIconWithBadge takes precedence over the icon argument.
                    if (it.size > 4) it[4] = null
                }
                // An avatar row yields (no whole-row replacement), but its corner badge is still
                // composed here from argument 0 — the bare app icon. Swap just the badge slots:
                // the host keeps the photo, the badge becomes the theme icon.
                context != null && entry != null ->
                    themedBadgeDrawable(context, entry)?.let { themed ->
                        chain.args.toTypedArray().also { args ->
                            args[0] = themed
                            args.getOrNull(4)?.let { large -> rebadgeWithTheme(large, themed) }
                        }
                    }
                else -> null
            }
            // Do not use `?.let { } ?: `: proceed() legitimately returns null (void setters),
            // which would fall through and invoke the target a second time.
            val result = if (newArgs != null) chain.proceed(newArgs) else chain.proceed()
            if (plan != null) {
                schedulePanelRenderPlan(banner, plan)
            } else if (context != null && entry != null) {
                refreshBannerIcon(context, banner, entry)
            }
            result
        }
    }

    private fun installBackgroundIconHook() {
        val method = members.backgroundSetIcon ?: return
        hooks.install(method, "systemui.fullscreen_banner.background.setIcon") { chain ->
            val context = (chain.instanceOrNull as? View)?.context
            val entry = lastEntry ?: return@install chain.proceed()
            val plan = context?.let { resolvePanelPlan(it, entry) }
            // Chain.getArgs() is immutable; pass the replacement through proceed().
            if (plan != null) {
                chain.proceed(chain.args.toTypedArray().also { it[0] = backgroundDrawable(plan.drawable) })
            } else {
                chain.proceed()
            }
        }
    }

    private fun patchBuilderIcon(
        context: Context,
        builder: Any,
        entry: Any,
    ): NotificationIconResolver.PanelIconRenderPlan? {
        val plan = resolvePanelPlan(context, entry)
        if (plan != null) {
            members.builderIcon?.set(builder, plan.drawable)
            members.builderAvatarIcon?.set(
                builder,
                plan.drawable.constantState?.newDrawable()?.mutate() ?: plan.drawable,
            )
            members.builderLargeIconWithBadge?.set(builder, null)
            return plan
        }
        // Avatar row: the banner keeps the host's "photo + corner badge" composition, so only the
        // badge slots are themed — Builder.mIcon is the badge source for the message-style branch,
        // and a pre-composed mLargeIconWithBadge carries the bare small icon as its badge.
        statusBarNotificationFrom(entry) ?: return null
        val themed = themedBadgeDrawable(context, entry) ?: return null
        members.builderIcon?.set(builder, themed)
        members.builderLargeIconWithBadge?.get(builder)?.let { rebadgeWithTheme(it, themed) }
        return null
    }

    /**
     * The theme icon for the badge slot of an avatar-bearing banner row, or null when replacement
     * is off / the package has no theme icon. Guarded by the same switches as the whole-row plan
     * so the banner never mixes a themed badge into an unthemed configuration.
     */
    private fun themedBadgeDrawable(
        context: Context,
        entry: Any,
    ): Drawable? {
        val snapshot = configuration.snapshot
        if (!snapshot.config.panelIconReplacementEnabled) return null
        if (!snapshot.resolver.panelThemeIconsEnabled()) return null
        val sbn = statusBarNotificationFrom(entry) ?: return null
        return try {
            snapshot.resolver.resolveThemeDrawableByPackage(context, sbn.packageName)
                ?.takeIf { configuration.isCurrent(snapshot) }
        } catch (exception: Exception) {
            null
        }
    }

    /**
     * Swaps the badge inside the host's pre-composed `DrawableWithBadge` (the Builder's
     * mLargeIconWithBadge slot) for the theme icon. The wrapper paints main + badge at draw time,
     * so a late `setBadge` call re-themes the corner without touching the photo underneath.
     */
    private fun rebadgeWithTheme(large: Any, themed: Drawable) {
        if (large.javaClass.name != DRAWABLE_WITH_BADGE_CLASS) return
        runCatching {
            large.javaClass
                .getMethod("setBadge", Drawable::class.java)
                .invoke(large, themed)
        }
    }

    private fun refreshBannerIcon(context: Context, banner: Any, entry: Any) {
        resolvePanelPlan(context, entry)?.let { plan ->
            schedulePanelRenderPlan(banner, plan)
        }
    }

    private fun bannerContext(banner: Any): Context? =
        (banner as? View)?.context ?: members.helperContext?.let { field ->
            members.bannerHelper?.get(banner)?.let { helper ->
                field.get(helper) as? Context
            }
        }

    private fun resolveEntry(banner: Any): Any? {
        members.lastNotificationEntry?.let { field ->
            val helper = members.bannerHelper?.get(banner) ?: return@let
            (field.get(helper))?.let { return it }
        }
        return lastEntry
    }

    private fun schedulePanelRenderPlan(
        banner: Any,
        plan: NotificationIconResolver.PanelIconRenderPlan,
    ) {
        applyPanelRenderPlan(banner, plan)
        val iconView = members.bannerIconView?.get(banner) as? View ?: return
        iconView.post { applyPanelRenderPlan(banner, plan) }
    }

    private fun applyPanelRenderPlan(
        banner: Any,
        plan: NotificationIconResolver.PanelIconRenderPlan,
    ) {
        val iconView = members.bannerIconView?.get(banner) as? ImageView ?: return
        iconView.clearColorFilter()
        iconView.imageTintList = null
        iconView.setImageDrawable(plan.drawable)
        plan.tintColor?.let { tint ->
            iconView.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        }
    }

    private fun backgroundDrawable(source: Drawable): Drawable =
        source.constantState?.newDrawable()?.mutate() ?: source

    private fun resolvePanelPlan(
        context: Context,
        entry: Any,
    ): NotificationIconResolver.PanelIconRenderPlan? {
        val snapshot = configuration.snapshot
        if (!snapshot.config.panelIconReplacementEnabled) return null
        val sbn = statusBarNotificationFrom(entry) ?: return null
        return try {
            snapshot.resolver.resolvePanelIconPlan(
                context = context,
                sbn = sbn,
                originalSmallIcon = sbn.originalSmallIcon(diagnostics, snapshot.revision),
            )?.takeIf { configuration.isCurrent(snapshot) }
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fullscreen_banner:resolve",
                message = "全屏轻反馈图标解析失败",
                cause = exception,
                revision = snapshot.revision,
            )
            null
        }
    }

    private fun statusBarNotificationFrom(entry: Any?): StatusBarNotification? {
        if (entry == null) return null
        return try {
            members.notificationEntryGetSbn.invoke(entry) as? StatusBarNotification
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fullscreen_banner:entry",
                message = "读取全屏轻反馈通知 entry 失败",
                cause = exception,
                revision = configuration.snapshot.revision,
            )
            null
        }
    }
}
