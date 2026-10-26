package com.c17.notify.replace.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.c17.notify.replace.R
import com.c17.notify.replace.ui.home.HomeScreenState
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun ModuleSettingsSection(
    state: HomeScreenState,
    onRulesEnabledChange: (Boolean) -> Unit,
    onPanelIconReplacementEnabledChange: (Boolean) -> Unit,
    onLockScreenCapsuleIconReplacementEnabledChange: (Boolean) -> Unit,
    onOplusPushSpecialHandlingEnabledChange: (Boolean) -> Unit,
    onPlaceholderIconEnabledChange: (Boolean) -> Unit,
) {
    val canEditConfig = state.canEditConfig
    ToggleComponent(
        title = stringResource(R.string.label_icon_enhancement_enabled),
        checked = state.config.rulesEnabled,
        enabled = canEditConfig,
        onCheckedChange = onRulesEnabledChange,
    )
    ToggleComponent(
        title = stringResource(R.string.label_panel_icon_replacement_enabled),
        checked = state.config.panelIconReplacementEnabled,
        enabled = canEditConfig,
        onCheckedChange = onPanelIconReplacementEnabledChange,
    )
    ToggleComponent(
        title = stringResource(R.string.label_lock_screen_capsule_icon_replacement_enabled),
        checked = state.config.lockScreenCapsuleIconReplacementEnabled,
        enabled = canEditConfig,
        onCheckedChange = onLockScreenCapsuleIconReplacementEnabledChange,
    )
    // The two rule-library options belong to the status bar, whose source is always the rule
    // library, so they stay available instead of following a global source switch.
    ToggleComponent(
        title = stringResource(R.string.label_oplus_push_special_handling_enabled),
        checked = state.config.oplusPushSpecialHandlingEnabled,
        enabled = canEditConfig && state.config.rulesEnabled,
        onCheckedChange = onOplusPushSpecialHandlingEnabledChange,
    )
    ToggleComponent(
        title = stringResource(R.string.label_placeholder_icon_enabled),
        checked = state.config.placeholderIconEnabled,
        enabled = canEditConfig && state.config.rulesEnabled,
        onCheckedChange = onPlaceholderIconEnabledChange,
    )
}

/**
 * The personal-avatar switch lives in a card of its own.
 *
 * It is the one option that *turns the module off* for a package — the host draws the contact photo
 * and the module stays out of the way — so it reads as a separate decision rather than one more
 * toggle in the icon list.
 */
@Composable
fun HostAvatarSection(
    state: HomeScreenState,
    onHostAvatarPriorityEnabledChange: (Boolean) -> Unit,
) {
    ToggleComponent(
        title = stringResource(R.string.label_host_avatar_priority),
        checked = state.config.hostAvatarPriorityEnabled,
        enabled = state.canEditConfig,
        onCheckedChange = onHostAvatarPriorityEnabledChange,
    )
}

@Composable
private fun ToggleComponent(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchPreference(
        title = title,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
    )
}
