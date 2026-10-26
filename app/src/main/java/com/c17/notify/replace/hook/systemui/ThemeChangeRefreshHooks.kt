package com.c17.notify.replace.hook.systemui

import android.os.Handler
import android.os.Looper
import com.c17.notify.replace.diagnostics.DiagnosticEvent
import com.c17.notify.replace.diagnostics.DiagnosticLevel
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.diagnostics.OccurrencePolicy
import com.c17.notify.replace.hook.HookRegistrar
import com.c17.notify.replace.hook.icon.ThemeIconProvider
import com.c17.notify.replace.hook.runtimeFailure

/**
 * Re-themes the rows that are already on screen after the user switches the system theme or the
 * desktop icon style.
 *
 * Those two switches only write `OplusExtraConfiguration`'s vendor fields. The public
 * [android.content.res.Configuration] is unchanged, so `android.app.ConfigurationController` never
 * calls back into SystemUI: every visible row keeps the artwork it resolved before the switch, and
 * only a new post (which rebinds its row) shows the new icon — the "切换主题后要等来一条新通知"
 * report. ColorOS funnels all resource application through
 * `OplusCommonInjector.applyConfigurationToResourcesForResourcesManager(Configuration, int)`, whose
 * `int` carries the change flags, so the switch is observed there instead.
 *
 * The flags are the vendor constants themselves: `THEME_CHANGED` matches
 * `OplusExtraConfiguration.THEME_OLD_SKIN_CHANGED`, `UX_ICON_CONFIG_CHANGED` matches
 * `OplusExtraConfiguration.UX_ICON_CONFIG_CHANGED`.
 */
internal class ThemeChangeRefreshHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val configuration: SystemUiConfiguration,
    private val themeIcons: ThemeIconProvider,
    private val members: ThemeChangeMembers,
    /** Rebinds the visible notification views; supplied by `SystemUiRuntime`. */
    private val refreshViews: (Long) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Only the newest configuration change matters: ColorOS applies resources several times in a
     * row while a theme packs itself, and each round would otherwise queue another full rebind.
     */
    private var pendingRefresh: Runnable? = null

    fun install() {
        hooks.install(
            members.applyConfigurationToResources,
            "systemui.theme_change",
        ) { chain ->
            // The host applies the new configuration inside this call; read the flags only after it
            // finished, otherwise the theme cache would be rebuilt against the old resources.
            val result = chain.proceed()
            val changes = chain.args.getOrNull(1) as? Int ?: 0
            if (changes and THEME_CHANGE_MASK != 0) scheduleRefresh(changes)
            result
        }
    }

    private fun scheduleRefresh(changes: Int) {
        // The vendor fields the theme cache keys on are already new at this point, so the stale
        // entries can go right away; the rebind below is what actually repaints the rows.
        try {
            themeIcons.clearCache()
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_change:clear_cache",
                message = "主题切换后清理图标缓存失败，等待缓存自然过期",
                cause = exception,
                revision = configuration.snapshot.revision,
            )
        }

        val previous = pendingRefresh
        val task = Runnable {
            pendingRefresh = null
            refreshViewsNow(changes)
        }
        pendingRefresh = task
        if (previous != null) mainHandler.removeCallbacks(previous)
        val posted = try {
            mainHandler.postDelayed(task, REBIND_DELAY_MILLIS)
        } catch (exception: Exception) {
            pendingRefresh = null
            diagnostics.runtimeFailure(
                scope = "theme_change:schedule",
                message = "主题切换后无法调度通知视图刷新，等待通知自然更新",
                cause = exception,
                revision = configuration.snapshot.revision,
            )
            return
        }
        if (!posted) {
            pendingRefresh = null
            diagnostics.runtimeFailure(
                scope = "theme_change:schedule",
                message = "主题切换后无法调度通知视图刷新，等待通知自然更新",
                cause = IllegalStateException("Main thread rejected the theme refresh"),
                revision = configuration.snapshot.revision,
            )
        }
    }

    private fun refreshViewsNow(changes: Int) {
        val snapshot = configuration.snapshot
        try {
            refreshViews(snapshot.revision)
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "theme_change:rebind",
                message = "主题切换后刷新通知视图失败，等待通知自然更新",
                cause = exception,
                revision = snapshot.revision,
            )
            return
        }
        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.ThemeChanged,
            message = "主题或桌面图标样式已切换，已请求重建通知图标",
            attributes = mapOf(
                "changes" to changes.toString(),
                "theme" to (changes and THEME_CHANGED != 0).toString(),
                "ux_icon" to (changes and UX_ICON_CONFIG_CHANGED != 0).toString(),
            ),
            occurrence = OccurrencePolicy.RateLimited("theme_change:rebind", 5_000L),
        )
    }

    private companion object {
        /** `OplusExtraConfiguration.THEME_OLD_SKIN_CHANGED` */
        const val THEME_CHANGED = 0x8000000

        /** `OplusExtraConfiguration.UX_ICON_CONFIG_CHANGED` */
        const val UX_ICON_CONFIG_CHANGED = 0x800000

        const val THEME_CHANGE_MASK = THEME_CHANGED or UX_ICON_CONFIG_CHANGED

        /** Long enough for the new resources to be visible, short enough to feel immediate. */
        const val REBIND_DELAY_MILLIS = 250L
    }
}
