package com.c17.notify.replace.hook.icon

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toDrawable
import com.c17.notify.replace.diagnostics.DiagnosticEvent
import com.c17.notify.replace.diagnostics.DiagnosticLevel
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.diagnostics.OccurrencePolicy
import com.c17.notify.replace.hook.runtimeFailure
import com.c17.notify.replace.rules.IconRule
import com.c17.notify.replace.rules.RuleStore

internal class NotificationIconResolver(
    config: RuleStore.ModuleConfig,
    /**
     * False while the placeholder configuration still stands in — see
     * [RuleStore.unloadedModuleConfig]. Avatar-capable surfaces then yield unconditionally:
     * an empty pick set is "not known yet", not "nothing picked", and a replacement that lands
     * on the host's avatar composition can no longer be corrected by it.
     */
    private val configLoaded: Boolean = true,
    private val rules: Map<String, IconRule>,
    private val themeIcons: ThemeIconProvider,
    private val iconConfiguration: OplusIconConfigurationReader,
    private val diagnostics: Diagnostics,
    private val revision: Long,
) {
    private val policyConfig = config.toPolicyConfig()

    data class StatusBarIconRenderPlan(
        val icon: Icon,
        val isColorable: Boolean,
    )

    data class PanelIconRenderPlan(
        val drawable: Drawable,
        val tintColor: Int?,
    )

    /**
     * Desktop theme drawable for the notification icon ColorOS composes itself.
     *
     * The avatar badge, expanded group rows and the lock-screen icon list read their app icon from
     * a single host helper (`NotificationResolverExImpl.getPackageIconForNotification`) and never
     * touch the views this class patches, so those paths ask for the finished drawable here.
     * Avatar-priority packages are *not* excluded: yielding there is exactly what left the badge
     * showing the raw app icon, and the composition keeps its own contact photo either way.
     */
    fun resolvePanelThemeDrawable(
        context: Context,
        sbn: StatusBarNotification,
    ): Drawable? = try {
        themeIcons.resolve(context, sbn)?.toDrawable(context.resources)
    } catch (exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "icon_resolver:panel:theme_drawable",
            message = "桌面主题图标解码失败，保留 ColorOS 原图标",
            cause = exception,
            revision = revision,
        )
        null
    }

    /**
     * Desktop theme drawable for the host paths that only hand over a package name — the lock-screen
     * hero and capsule in "hide notification content" mode never pass a StatusBarNotification.
     */
    fun resolveThemeDrawableByPackage(
        context: Context,
        packageName: String,
    ): Drawable? = try {
        themeIcons.resolveByPackage(context, packageName)?.toDrawable(context.resources)
    } catch (exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "icon_resolver:panel:theme_drawable:pkg",
            message = "按包名解析桌面主题图标失败，保留 ColorOS 原图标",
            cause = exception,
            revision = revision,
        )
        null
    }

    /** True when the notification centre currently draws desktop theme icons. */
    fun panelThemeIconsEnabled(): Boolean =
        NotificationIconPolicy.shouldResolveTheme(policyConfig, IconSurface.Panel)

    /**
     * Desktop theme icon as an [Icon] for the lock-screen capsule, which carries both an [Icon] and a
     * [Drawable] slot and therefore cannot use [resolvePanelThemeDrawable] alone.
     */
    fun resolveLockScreenThemeIcon(
        context: Context,
        sbn: StatusBarNotification,
    ): Icon? = try {
        themeIcons.resolve(context, sbn)?.let { Icon.createWithBitmap(it) }
    } catch (exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "icon_resolver:lockscreen:theme_icon",
            message = "锁屏胶囊主题图标解码失败，交回宿主实现",
            cause = exception,
            revision = revision,
        )
        null
    }

    fun resolveStatusBarIconPlan(
        context: Context,
        sbn: StatusBarNotification,
        originalSmallIcon: Icon?,
    ): StatusBarIconRenderPlan? = resolveOrFallback("status_bar", "状态栏图标解析失败") {
        if (shouldKeepHostDefault(sbn)) return null
        resolveThemeIconReplacement(context, sbn, IconSurface.StatusBar)
            ?.toStatusBarIconRenderPlanOrNull(context, "status_bar")
            ?.let { return it }
        val forcedReplacement = resolveRuleIconReplacement(
            sbn,
            IconSurface.StatusBar,
            OriginalIconCompatibility.Unchecked,
        )
        forcedReplacement
            ?.toStatusBarIconRenderPlanOrNull(context, "status_bar")
            ?.let { return it }

        val originalDrawable = originalSmallIcon?.loadDrawable(context)?.mutate() ?: return null
        val originalCompatibility = NotificationIconMaskClassifier.classify(originalDrawable)
        if (forcedReplacement == null) {
            resolveRuleIconReplacement(
                sbn,
                IconSurface.StatusBar,
                originalCompatibility.toPolicyCompatibility(),
            )
                ?.toStatusBarIconRenderPlanOrNull(context, "status_bar")
                ?.let { return it }
        }
        originalSmallIcon
            .takeIf { originalCompatibility == NotificationIconMaskCompatibility.Compatible }
            ?.let { icon -> StatusBarIconRenderPlan(icon = icon, isColorable = true) }
    }

    @Suppress("UNUSED_PARAMETER") // Kept in the signature: every panel caller already has the icon.
    fun resolvePanelIconPlan(
        context: Context,
        sbn: StatusBarNotification,
        originalSmallIcon: Icon?,
    ): PanelIconRenderPlan? = resolveOrFallback("panel", "通知面板图标解析失败") {
        // ColorOS renders the "personal avatar + app-icon badge" composition itself for the picked
        // packages. Leave those rows, banner cards and lock-screen capsules untouched — but only
        // when this notification has an avatar for the host to compose (see the resolver method).
        if (shouldYieldToHostAvatar(context, sbn.packageName, sbn)) return@resolveOrFallback null
        if (
            !NotificationIconPolicy.shouldProcessPanel(
                config = policyConfig,
                isMediaNotification = sbn.isMediaNotification(),
                isOplusPush = sbn.isOplusPush(),
            )
        ) return@resolveOrFallback null
        // This surface has exactly one source: [IconSurface.Panel] → [PolicyIconSource.DesktopTheme].
        // There is deliberately no rule-mask and no raw-small-icon fallback here. Both are
        // monochrome artworks meant for the status bar, where SystemUI tints them and draws its own
        // background; painted into a row they arrive as-is — no squircle, no tint — which is the
        // bare white silhouette reported as "pdd / 天气又变成规则图标了". The fallbacks also race the
        // theme engine's cold start: a miss at bind time pinned the mask onto the row until the next
        // post rebound it, which is why the same row fixed itself the moment a new notification came
        // in. Answering null instead keeps ColorOS' own artwork and hands the row to the caller's
        // guard, which retries until the theme icon resolves.
        resolveThemeIconReplacement(context, sbn, IconSurface.Panel)
            ?.toPanelIconRenderPlanOrNull(context, "panel")
    }

    /**
     * The collapsed group row is the app's own summary: ColorOS draws the app icon plus a count
     * badge there (`GroupIconManager` / `OplusNotificationGroupExtImpl.attachCollapsedGroupBadge`)
     * and never a contact avatar, so honouring the avatar here would only replace a themed icon with
     * the plain one. That row therefore keeps the configured icon source.
     */
    @Suppress("UNUSED_PARAMETER") // Kept in the signature: every panel caller already has the icon.
    fun resolveGroupSummaryIconPlan(
        context: Context,
        sbn: StatusBarNotification,
        originalSmallIcon: Icon?,
    ): PanelIconRenderPlan? = resolveOrFallback("panel", "通知面板图标解析失败") {
        // A collapsed group can still carry a real sender photo in its large icon (WeCom and
        // WorkBuddy post one for the aggregate row). ColorOS composes "avatar + badge" for it by
        // itself, so painting the theme icon there would fight the host: the row would flip between
        // the theme icon and the avatar every time GroupIconManager repaints — exactly the "图标跳
        // 变成个人头像+角标" flicker. Yield to the host whenever there is a photo to compose; the
        // corner badge is already the theme icon through the composition hook.
        val avatar = HostAvatarPresence.evaluate(context, sbn, allowGroupSummaryAvatar = true)
        if (avatar.renderable) return@resolveOrFallback null
        // No "keep the original small icon" fallback here. On a collapsed group that fallback is
        // exactly the bug it looks like: the monochrome notification mask (SMS: a plain white
        // speech bubble) painted straight into the icon box, with the host then deriving the pill
        // colour from that white artwork — a blank white squircle next to the count badge. Leaving
        // the row alone keeps ColorOS' own app icon, which is what the group row should show.
        //
        // Rule masks and the placeholder are refused for the same reason, so this row only accepts
        // the real desktop theme icon. Those fallbacks are monochrome masks meant for the status
        // bar, where SystemUI tints them; the panel draws them as-is, which is the white disc the
        // user sees as "农行出现了规则图标". Whenever the theme icon cannot be resolved we return
        // null and the host keeps the app icon it already has.
        if (
            !NotificationIconPolicy.shouldProcessPanel(
                config = policyConfig,
                isMediaNotification = sbn.isMediaNotification(),
                isOplusPush = sbn.isOplusPush(),
            )
        ) return@resolveOrFallback null
        resolveThemeIconReplacement(context, sbn, IconSurface.Panel)
            ?.toPanelIconRenderPlanOrNull(context, "panel")
    }

    private fun resolveThemeIconReplacement(
        context: Context,
        sbn: StatusBarNotification,
        surface: IconSurface,
    ): IconReplacement? {
        if (!NotificationIconPolicy.shouldResolveTheme(policyConfig, surface)) return null
        return themeIcons.resolve(context, sbn)?.let(IconReplacement::ThemeIcon)
    }

    private fun resolveRuleIconReplacement(
        sbn: StatusBarNotification,
        surface: IconSurface,
        originalCompatibility: OriginalIconCompatibility,
    ): IconReplacement? {
        val rule = rules[sbn.rulePackageName()]
        return when (
            NotificationIconPolicy.selectRuleReplacement(
                config = policyConfig,
                surface = surface,
                ruleEnabled = rule?.isEnabled == true,
                ruleEnabledAll = rule?.isEnabledAll == true,
                isOplusPush = sbn.isOplusPush(),
                originalCompatibility = originalCompatibility,
            )
        ) {
            RuleReplacement.Rule -> rule?.let(IconReplacement::RuleIcon)
            RuleReplacement.Placeholder -> IconReplacement.Placeholder
            RuleReplacement.None -> null
        }
    }

    private fun IconReplacement.toStatusBarIconRenderPlan(
        context: Context,
    ): StatusBarIconRenderPlan = StatusBarIconRenderPlan(
        icon = when (this) {
            is IconReplacement.ThemeIcon -> Icon.createWithBitmap(bitmap)
            is IconReplacement.RuleIcon -> Icon.createWithBitmap(
                rule.iconBitmap.withDarkEffect(context.isDarkIconEffectEnabled())
            )
            IconReplacement.Placeholder -> Icon.createWithBitmap(PlaceholderIconFactory.createBitmap())
        },
        // Theme icons are full-color launcher assets. Rule, placeholder and restored original
        // icons are notification masks whose RGB is replaced by SystemUI.
        isColorable = this !is IconReplacement.ThemeIcon,
    )

    private fun IconReplacement.toStatusBarIconRenderPlanOrNull(
        context: Context,
        feature: String,
    ): StatusBarIconRenderPlan? = try {
        toStatusBarIconRenderPlan(context)
    } catch (exception: Exception) {
        reportReplacementFailure(feature, exception)
        null
    }

    private fun IconReplacement.toPanelIconRenderPlan(context: Context): PanelIconRenderPlan {
        return when (this) {
            is IconReplacement.ThemeIcon -> PanelIconRenderPlan(
                drawable = bitmap.toDrawable(context.resources),
                tintColor = null,
            )
            is IconReplacement.RuleIcon -> PanelIconRenderPlan(
                drawable = rule.iconBitmap.toDrawable(context.resources),
                tintColor = (rule.iconColor.takeIf { it != 0 } ?: context.defaultPanelIconTint)
                    .withDarkEffect(context.isDarkIconEffectEnabled()),
            )
            IconReplacement.Placeholder -> PanelIconRenderPlan(
                drawable = PlaceholderIconFactory.createBitmap().toDrawable(context.resources),
                tintColor = context.defaultPanelIconTint,
            )
        }
    }

    private fun IconReplacement.toPanelIconRenderPlanOrNull(
        context: Context,
        feature: String,
    ): PanelIconRenderPlan? = try {
        toPanelIconRenderPlan(context)
    } catch (exception: Exception) {
        reportReplacementFailure(feature, exception)
        null
    }

    private fun IconReplacement.reportReplacementFailure(feature: String, exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "icon_resolver:$feature:replacement:$diagnosticName",
            message = "候选图标无法解码或构造，跳过该候选",
            cause = exception,
            revision = revision,
        )
    }

    private fun Context.isDarkIconEffectEnabled(): Boolean {
        val configuration = iconConfiguration.read(this)
        return ThemeIconDarkEffect.isEnabled(configuration.uiMode, configuration.uxIconConfig)
    }

    private fun Bitmap.withDarkEffect(enabled: Boolean): Bitmap =
        if (enabled) ThemeIconDarkEffect.apply(this) else this

    private fun Int.withDarkEffect(enabled: Boolean): Int =
        if (enabled) ThemeIconDarkEffect.apply(this) else this

    private val Context.defaultPanelIconTint: Int
        get() {
            // ColorOS 17 paints the shade as liquid glass: the dark neutral the panel used in light
            // mode has no contrast against that backdrop, so the night colour is used in both
            // modes from 17 on.
            if (PlatformVersion.isColorOS17OrNewer) return DARK_PANEL_ICON_TINT
            val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
                DARK_PANEL_ICON_TINT
            } else {
                LIGHT_PANEL_ICON_TINT
            }
        }

    private inline fun <T> resolveOrFallback(
        feature: String,
        message: String,
        block: () -> T?,
    ): T? = try {
        block()
    } catch (exception: Exception) {
        diagnostics.runtimeFailure(
            scope = "icon_resolver:$feature",
            message = message,
            cause = exception,
            revision = revision,
        )
        null
    }

    private fun StatusBarNotification.rulePackageName(): String =
        packageName.orEmpty()

    fun shouldKeepHostDefault(sbn: StatusBarNotification): Boolean =
        NotificationIconPolicy.shouldKeepHostDefault(
            policyConfig,
            IconSurface.StatusBar,
            sbn.isOplusPush(),
        )

    /**
     * True when the user picked this package for the personal avatar and ColorOS is going to render
     * that avatar for *this* notification, in which case the avatar-capable surfaces must keep the
     * host result. Callers that render outside [resolvePanelIconPlan] (lock-screen capsule, AOD)
     * must ask before consulting `StatusBarIconReplacementCache`, otherwise a status-bar
     * replacement cached for the same package would still take the slot.
     *
     * A picked package is not enough by itself — [HostAvatarPresence] decides whether the host has
     * an avatar to compose. Without that, WeChat's "视频通话中" (an ongoing notification with no
     * contact photo) stays with the host and shows the bare small icon instead of the themed one.
     * Pass a null [sbn] on surfaces that only know the notification key; they keep yielding.
     *
     * The status bar deliberately never yields here: for these packages the host only shows an
     * avatar for important conversations, and `StatusBarHooks` already lets that case through.
     */
    fun shouldYieldToHostAvatar(
        context: Context,
        packageName: String?,
        sbn: StatusBarNotification?,
    ): Boolean {
        // The remote configuration is still being read: yield on every surface that can show
        // the avatar, including the ones (AOD) that would otherwise reuse a status-bar
        // replacement cached during this window.
        if (!configLoaded) return true
        // A row the app flags as a group summary is usually a plain aggregate, but WeChat/WeCom
        // service accounts post their own picture on exactly those rows ("[2条] …"). Ask the
        // artwork question there too: a real photo means the host is the one that can compose
        // "avatar + badge", so the row must yield; a launcher-icon placeholder still answers
        // `group-summary` and keeps the themed icon.
        val verdict = HostAvatarPresence.evaluate(
            context = context,
            sbn = sbn,
            allowGroupSummaryAvatar = HostAvatarPresence.isGroupSummary(sbn),
        )
        val hostAvatarAvailable = verdict.renderable
        val yield = NotificationIconPolicy.shouldYieldToHostAvatar(
            config = policyConfig,
            packageName = packageName,
            hostAvatarAvailable = hostAvatarAvailable,
        )
        if (!yield && sbn != null &&
            NotificationIconPolicy.isAvatarPriorityPackage(policyConfig, packageName)
        ) {
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.HostAvatarReplace,
                message = if (verdict.reason == HostAvatarPresence.REASON_LAUNCHER_PLACEHOLDER) {
                    "已勾选显示个人头像，但头像位是应用图标占位，按主题图标替换"
                } else {
                    "已勾选显示个人头像，但本条通知没有头像可合成，按图标来源替换"
                },
                attributes = mapOf(
                    "package" to packageName,
                    "key" to sbn.key,
                    "verdict" to verdict.reason,
                    "difference" to verdict.difference.toString(),
                ),
                occurrence = OccurrencePolicy.Once("icon:host_avatar_replace:$packageName"),
            )
        }
        if (yield) {
            // host_listed=false means ColorOS itself does not render an avatar for that package, so
            // the pick only removes the module's own icon there — handy while testing a selection.
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.HostAvatarYield,
                message = "宿主将显示联系人头像，本次不覆盖图标",
                attributes = mapOf(
                    "package" to packageName,
                    "key" to (sbn?.key ?: ""),
                    "host_listed" to HostAvatarPackages.contains(context, packageName),
                    "verdict" to verdict.reason,
                    "difference" to verdict.difference.toString(),
                    "summary" to HostAvatarPresence.isGroupSummary(sbn).toString(),
                ),
                occurrence = OccurrencePolicy.Once("icon:host_avatar:$packageName:${sbn?.key ?: ""}"),
            )
        }
        return yield
    }

    /**
     * Fills the window the host leaves open before its avatar composition lands.
     *
     * ColorOS runs `createBackgroundWithAppIcon` inside `withContext(Dispatchers.Default)`, so an
     * avatar row first paints the plain `smallIcon` (the app icon) and only then swaps to the
     * contact photo — the visible "先闪一下应用图标再跳成头像". When the module yields, it can
     * pre-compose the very same drawable synchronously and hand it to the icon view; the coroutine
     * result that arrives afterwards is the same picture, so the swap becomes invisible.
     *
     * Returns null — callers then leave the host untouchable — unless the notification really has
     * an avatar to compose and the module would have drawn a theme badge on top of it.
     */
    fun composeHostAvatarPreview(
        context: Context,
        sbn: StatusBarNotification,
        contentSizePx: Int,
        allowGroupSummaryAvatar: Boolean = false,
    ): Drawable? {
        if (!configLoaded) return null
        if (!NotificationIconPolicy.isAvatarPriorityPackage(policyConfig, sbn.packageName)) return null
        if (!HostAvatarPresence.contains(sbn)) return null
        if (!HostAvatarPresence.canRenderAvatar(context, sbn, allowGroupSummaryAvatar)) return null
        if (!panelThemeIconsEnabled()) return null
        val badge = resolveThemeDrawableByPackage(context, sbn.packageName) ?: return null
        val avatar = try {
            HostAvatarPresence.avatarDrawable(context, sbn)
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "icon_resolver:host_avatar_prefill",
                message = "预览头像解码失败，交给宿主异步合成",
                cause = exception,
                revision = revision,
            )
            null
        } ?: return null
        return HostAvatarComposition.compose(context, avatar, badge, sbn.packageName, contentSizePx)
    }

    fun shouldKeepHostAppIconBehavior(): Boolean =
        NotificationIconPolicy.shouldKeepHostAppIconBehavior(policyConfig, IconSurface.StatusBar)

    private fun StatusBarNotification.isOplusPush(): Boolean =
        opPkg == SYSTEM_FRAMEWORK_PACKAGE && opPkg != packageName

    private fun StatusBarNotification.isMediaNotification(): Boolean {
        if (notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true) return true
        return notification.category == Notification.CATEGORY_TRANSPORT
    }

    private fun NotificationIconMaskCompatibility.toPolicyCompatibility(): OriginalIconCompatibility =
        when (this) {
            NotificationIconMaskCompatibility.Compatible -> OriginalIconCompatibility.Compatible
            NotificationIconMaskCompatibility.Incompatible -> OriginalIconCompatibility.Incompatible
            NotificationIconMaskCompatibility.Unknown -> OriginalIconCompatibility.Unchecked
        }

    private sealed class IconReplacement(val diagnosticName: String) {
        data class ThemeIcon(val bitmap: Bitmap) : IconReplacement("theme")
        data class RuleIcon(val rule: IconRule) : IconReplacement("rule")
        object Placeholder : IconReplacement("placeholder")
    }

    private fun RuleStore.ModuleConfig.toPolicyConfig() = IconPolicyConfig(
        rulesEnabled = rulesEnabled,
        panelEnabled = panelIconReplacementEnabled,
        handleOplusPush = oplusPushSpecialHandlingEnabled,
        placeholderEnabled = placeholderIconEnabled,
        hostAvatarPriorityEnabled = hostAvatarPriorityEnabled,
        avatarPriorityPackages = avatarPriorityPackages,
    )

    private companion object {
        const val SYSTEM_FRAMEWORK_PACKAGE = "android"
        const val LIGHT_PANEL_ICON_TINT = 0xFF707173.toInt()
        const val DARK_PANEL_ICON_TINT = 0xFFDCDCDC.toInt()
    }
}
