package com.c17.notify.replace.ui.rules

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.c17.notify.replace.R
import com.c17.notify.replace.rules.IconRule
import com.c17.notify.replace.ui.component.CardSegment
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * One app's switchboard.
 *
 * The list screen only carries what a list is for — icon, name, whether the app is covered — one
 * row per app. The three switches that used to sit inside every row live here instead: five rows of
 * controls per app meant the list showed barely two apps per screen, and the switches were never
 * the part anyone scans for.
 */
@Composable
internal fun RuleDetailScreen(
    rule: IconRule,
    rulesEnabled: Boolean,
    canEditConfig: Boolean,
    avatarPriorityEnabled: Boolean,
    avatarPriority: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onEnabledAllChange: (Boolean) -> Unit,
    onAvatarPriorityChange: (Boolean) -> Unit,
    onChooseIcon: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val canChooseIcon = canEditConfig && rulesEnabled

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.rule_detail_title),
                largeTitle = rule.appName.ifBlank { rule.packageName },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.ChevronBackward,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                SmallTitle(text = stringResource(R.string.rule_detail_section_behavior))
            }
            item {
                CardSegment(isFirst = true, isLast = true) {
                    SwitchPreference(
                        title = stringResource(R.string.label_rule_enable),
                        checked = rule.isEnabled,
                        onCheckedChange = onEnabledChange,
                        enabled = canChooseIcon,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.label_rule_force_all),
                        checked = rule.isEnabledAll,
                        onCheckedChange = onEnabledAllChange,
                        enabled = canChooseIcon && rule.isEnabled,
                    )
                    SwitchPreference(
                        title = stringResource(R.string.label_host_avatar_priority),
                        checked = avatarPriority,
                        onCheckedChange = onAvatarPriorityChange,
                        enabled = canEditConfig && avatarPriorityEnabled,
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(12.dp)) }
            item {
                SmallTitle(text = stringResource(R.string.rule_detail_section_icon))
            }
            item {
                CardSegment(isFirst = true, isLast = true) {
                    ArrowPreference(
                        title = stringResource(R.string.label_rule_choose_icon),
                        summary = iconSourceSummary(rule),
                        onClick = onChooseIcon,
                        enabled = canChooseIcon,
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(12.dp)) }
            item {
                SmallTitle(text = stringResource(R.string.rule_detail_section_identity))
            }
            item {
                CardSegment(isFirst = true, isLast = true) {
                    IdentityRow(
                        title = stringResource(R.string.rule_detail_package),
                        value = rule.packageName,
                    )
                    if (rule.contributorName.isNotBlank()) {
                        IdentityRow(
                            title = stringResource(R.string.rule_detail_contributor),
                            value = rule.contributorName,
                        )
                    }
                }
            }
        }
    }
}

/** Key/value row: label on the leading edge, value right-aligned and truncated on one line. */
@Composable
private fun IdentityRow(
    title: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = title,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            modifier = Modifier.weight(2f),
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}
