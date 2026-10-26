package com.c17.notify.replace.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.c17.notify.replace.BuildConfig
import com.c17.notify.replace.R
import com.c17.notify.replace.core.ModuleInfo
import com.c17.notify.replace.diagnostics.AppDiagnostics
import com.c17.notify.replace.diagnostics.DiagnosticEvent
import com.c17.notify.replace.diagnostics.DiagnosticLevel
import com.c17.notify.replace.diagnostics.OccurrencePolicy
import com.c17.notify.replace.rules.RuleSourceConfig
import com.c17.notify.replace.rules.RuleSourceType
import com.c17.notify.replace.rules.readRuleSourceConfig
import com.c17.notify.replace.rules.writeRuleSourceConfig
import com.c17.notify.replace.ui.component.CardSegment
import com.c17.notify.replace.ui.component.MiuixBlurredTopBar
import com.c17.notify.replace.ui.component.rememberMiuixPageBackdrop
import com.c17.notify.replace.ui.theme.C17NotifyReplaceTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val SOFTWARE_VERSION_PROPERTY = "ro.build.display.id.show"

@Composable
fun HomeScreen(
    state: HomeScreenState,
    bottomPadding: Dp,
    snackbarHostState: SnackbarHostState,
    onSyncRules: (RuleSourceConfig, (String) -> Unit) -> Unit,
    onRestartSystemUi: ((String) -> Unit) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberMiuixPageBackdrop()
    val surfaceColor = MiuixTheme.colorScheme.surface
    var showRestartDialog by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var ruleSource by remember { mutableStateOf(readRuleSourceConfig(context)) }
    var pendingSourceType by remember { mutableStateOf(ruleSource.type) }
    var repositoryInvalid by remember { mutableStateOf(false) }
    val repositoryState = rememberTextFieldState()
    val scope = rememberCoroutineScope()

    fun showSnackbar(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun openSourceDialog() {
        pendingSourceType = ruleSource.type
        repositoryInvalid = false
        repositoryState.setTextAndPlaceCursorAtEnd(ruleSource.repository)
        showSourceDialog = true
    }

    fun confirmSourceAndSync() {
        val repository = RuleSourceConfig.normalizeRepository(repositoryState.text.toString())
        if (repository == null) {
            repositoryInvalid = true
            return
        }
        val config = RuleSourceConfig(type = pendingSourceType, repository = repository)
        writeRuleSourceConfig(context, config)
        ruleSource = config
        showSourceDialog = false
        onSyncRules(config, ::showSnackbar)
    }

    Scaffold(
        topBar = {
            MiuixBlurredTopBar(backdrop) {
                TopAppBar(
                    title = stringResource(R.string.app_name),
                    largeTitle = stringResource(R.string.home_title),
                    color = if (backdrop != null) Color.Transparent else surfaceColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = maxOf(bottomPadding, innerPadding.calculateBottomPadding()),
            ),
        ) {
            item { StatusOverview(state = state) }
            item {
                RulesCard(
                    state = state,
                    sourceType = ruleSource.type,
                    onSyncRules = ::openSourceDialog,
                    onRestartClick = { showRestartDialog = true },
                )
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    RestartDialog(
        show = showRestartDialog,
        onDismiss = { showRestartDialog = false },
        onConfirm = {
            showRestartDialog = false
            onRestartSystemUi(::showSnackbar)
        },
    )

    RuleSourceDialog(
        show = showSourceDialog,
        selected = pendingSourceType,
        repositoryState = repositoryState,
        repositoryInvalid = repositoryInvalid,
        onSelect = {
            pendingSourceType = it
            repositoryInvalid = false
        },
        onDismiss = { showSourceDialog = false },
        onOpenCloud = { context.openUrl(ModuleInfo.ANIP_PROJECT_URL) },
        onConfirm = ::confirmSourceAndSync,
    )
}

private fun Context.openUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Source picker shown before a rule sync.
 *
 * Same shape as `fankes/ColorOSNotifyIcon`'s "同步列表" sheet: pick the route that reaches GitHub on
 * the current network, optionally point at another repository, then confirm to start the download.
 * The choice is what "更新规则" uses from then on, so it only has to be made once per network.
 */
@Composable
private fun RuleSourceDialog(
    show: Boolean,
    selected: RuleSourceType,
    repositoryState: TextFieldState,
    repositoryInvalid: Boolean,
    onSelect: (RuleSourceType) -> Unit,
    onDismiss: () -> Unit,
    onOpenCloud: () -> Unit,
    onConfirm: () -> Unit,
) {
    // The error clears itself once the text becomes a valid slug again, so no edit callback is needed.
    val showRepositoryError =
        repositoryInvalid && RuleSourceConfig.normalizeRepository(repositoryState.text.toString()) == null
    OverlayDialog(
        show = show,
        title = stringResource(R.string.dialog_rule_source_title),
        summary = stringResource(R.string.dialog_rule_source_summary),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RuleSourceType.entries.forEach { type ->
                RuleSourceOptionRow(
                    label = stringResource(ruleSourceLabelRes(type)),
                    hint = stringResource(ruleSourceHintRes(type)),
                    selected = type == selected,
                    onClick = { onSelect(type) },
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.dialog_rule_source_repository),
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.weight(1f))
            // A secondary entry point rather than a third full-width button: the sheet stays at
            // two actions, so the confirm row keeps its weight.
            Text(
                text = stringResource(R.string.dialog_rule_source_cloud),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenCloud)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextField(
            state = repositoryState,
            modifier = Modifier.fillMaxWidth(),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
        )
        if (showRepositoryError) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.dialog_rule_source_repository_invalid),
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(
                text = stringResource(R.string.dialog_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = stringResource(R.string.dialog_confirm_sync),
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun RuleSourceOptionRow(
    label: String,
    hint: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            maxLines = 1,
            color = if (selected) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurface
            },
        )
        Spacer(Modifier.width(10.dp))
        // The host name rides on the same line instead of wrapping under the label; that keeps the
        // three routes to three rows.
        Text(
            modifier = Modifier.weight(1f),
            text = hint,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Text(
                    text = "✓",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun ruleSourceLabelRes(type: RuleSourceType): Int = when (type) {
    RuleSourceType.GhProxy1 -> R.string.rule_source_gh_proxy_1
    RuleSourceType.GhProxy2 -> R.string.rule_source_gh_proxy_2
    RuleSourceType.GitHubDirect -> R.string.rule_source_direct
}

private fun ruleSourceHintRes(type: RuleSourceType): Int = when (type) {
    RuleSourceType.GhProxy1 -> R.string.rule_source_gh_proxy_1_hint
    RuleSourceType.GhProxy2 -> R.string.rule_source_gh_proxy_2_hint
    RuleSourceType.GitHubDirect -> R.string.rule_source_direct_hint
}

@Composable
private fun StatusOverview(state: HomeScreenState) {
    val context = LocalContext.current
    val moduleVersionText = stringResource(
        R.string.status_hero_module_version,
        BuildConfig.VERSION_NAME,
        BuildConfig.VERSION_CODE,
    )
    val softwareVersionText = remember { softwareVersionText() }
    val heroSpec = statusHeroSpec(context, state, moduleVersionText)

    Column(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusHeroCard(
            spec = heroSpec,
            rulesCount = state.rulesCount,
            enabledRulesCount = state.enabledRulesCount,
        )
        SystemInfoCard(
            state = state,
            moduleVersionText = moduleVersionText,
            softwareVersionText = softwareVersionText,
        )
    }
}

@Composable
private fun StatusHeroCard(
    spec: StatusHeroSpec,
    rulesCount: Int,
    enabledRulesCount: Int,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        insideMargin = PaddingValues(),
        colors = CardDefaults.defaultColors(
            color = spec.containerColor,
            contentColor = spec.accentColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = spec.icon,
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                tint = spec.accentColor,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = spec.status,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = spec.versionLine,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatBlock(value = rulesCount.toString(), label = stringResource(R.string.home_card_rules))
            Spacer(Modifier.width(18.dp))
            StatBlock(value = enabledRulesCount.toString(), label = stringResource(R.string.home_card_enabled))
        }
    }
}

/** A number and its label: the two figures that used to occupy half the screen as cards. */
@Composable
private fun StatBlock(
    value: String,
    label: String,
) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SystemInfoCard(
    state: HomeScreenState,
    moduleVersionText: String,
    softwareVersionText: String,
) {
    val deviceText = remember {
        listOf(Build.BRAND, Build.MODEL)
            .map { it.orEmpty().trim() }
            .filter(String::isNotEmpty)
            .joinToString(" ")
            .ifBlank { Build.DEVICE.orEmpty() }
    }
    val framework = state.frameworkConnection
    val frameworkText = if (framework == null) {
        stringResource(R.string.home_info_framework_disconnected)
    } else {
        "${framework.name} ${framework.version} · API ${framework.apiVersion}"
    }
    val scopesText = when {
        framework == null -> stringResource(R.string.status_hero_inactive_detail)
        state.missingScopes.isNotEmpty() -> stringResource(
            R.string.status_hero_missing_scopes_detail,
            state.missingScopes.joinToString(),
        )
        else -> stringResource(R.string.home_info_scopes_ready)
    }
    SmallTitle(text = stringResource(R.string.home_info_title))
    CardSegment(isFirst = true, isLast = true) {
        InfoRow(
            title = stringResource(R.string.home_info_system_version),
            content = softwareVersionText.ifBlank { deviceText },
        )
        InfoRow(
            title = stringResource(R.string.home_info_device),
            content = deviceText,
        )
        InfoRow(
            title = stringResource(R.string.home_info_module_version),
            content = moduleVersionText,
        )
        InfoRow(
            title = stringResource(R.string.home_info_framework),
            content = frameworkText,
        )
        InfoRow(
            title = stringResource(R.string.home_info_scopes),
            content = scopesText,
        )
    }
}

/** Label on the leading edge, value right-aligned on one line: the density a settings page has. */
@Composable
private fun InfoRow(
    title: String,
    content: String,
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
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            modifier = Modifier.weight(2f),
            text = content,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun RulesCard(
    state: HomeScreenState,
    sourceType: RuleSourceType,
    onSyncRules: () -> Unit,
    onRestartClick: () -> Unit,
) {
    val lastSyncText = remember(state.rulesUpdatedAt) {
        if (state.rulesUpdatedAt > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(state.rulesUpdatedAt))
        } else {
            null
        }
    }
    val syncSummary = if (lastSyncText == null) {
        stringResource(R.string.label_rules_never_synced)
    } else {
        stringResource(R.string.label_rules_last_sync, lastSyncText)
    }
    val sourceSummary = stringResource(
        R.string.label_rules_source,
        stringResource(ruleSourceLabelRes(sourceType)),
    )

    SmallTitle(text = stringResource(R.string.home_quick_actions))
    CardSegment(isFirst = true, isLast = true) {
        ArrowPreference(
            title = when (state.syncStage) {
                RuleSyncStage.Idle -> stringResource(R.string.button_sync_rules)
                RuleSyncStage.SyncingRules -> stringResource(R.string.button_syncing_rules)
                RuleSyncStage.MirroringRemote -> stringResource(R.string.button_mirroring_rules)
            },
            summary = "$syncSummary · $sourceSummary",
            onClick = onSyncRules,
            enabled = !state.isSyncing && state.canEditConfig,
        )
        ArrowPreference(
            title = stringResource(R.string.label_restart_systemui),
            onClick = onRestartClick,
            enabled = state.canEditConfig,
        )
    }
}

@Composable
private fun RestartDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.dialog_restart_title),
        summary = stringResource(R.string.dialog_restart_message),
        onDismissRequest = onDismiss,
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                text = stringResource(R.string.dialog_cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            TextButton(
                text = stringResource(R.string.dialog_confirm_restart),
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun statusHeroSpec(
    context: android.content.Context,
    state: HomeScreenState,
    moduleVersionText: String,
): StatusHeroSpec {
    val frameworkConnection = state.frameworkConnection
    val dark = isSystemInDarkTheme()
    return when {
        frameworkConnection == null -> {
            StatusHeroSpec(
                status = context.getString(R.string.status_hero_inactive_title),
                versionLine = moduleVersionText,
                accentColor = Color(0xFFFF453A),
                containerColor = if (dark) Color(0xFF3A1A1A) else Color(0xFFFFEBEA),
                icon = StatusIcons.ErrorOutline,
            )
        }
        state.missingScopes.isNotEmpty() -> {
            StatusHeroSpec(
                status = context.getString(R.string.status_hero_missing_scopes_title),
                versionLine = moduleApiText(
                    context = context,
                    moduleVersionText = moduleVersionText,
                    apiVersion = frameworkConnection.apiVersion,
                ),
                accentColor = Color(0xFFFF9500),
                containerColor = if (dark) Color(0xFF3A2A12) else Color(0xFFFFF4E0),
                icon = StatusIcons.ErrorOutline,
            )
        }
        else -> {
            StatusHeroSpec(
                status = context.getString(R.string.status_hero_active_title),
                versionLine = moduleApiText(
                    context = context,
                    moduleVersionText = moduleVersionText,
                    apiVersion = frameworkConnection.apiVersion,
                ),
                accentColor = Color(0xFF36D167),
                containerColor = if (dark) Color(0xFF1A3825) else Color(0xFFDFFAE4),
                icon = StatusIcons.CheckCircleOutline,
            )
        }
    }
}

private fun moduleApiText(
    context: android.content.Context,
    moduleVersionText: String,
    apiVersion: Int,
): String = context.getString(
    R.string.status_hero_module_api,
    moduleVersionText,
    apiVersion,
)

private data class StatusHeroSpec(
    val status: String,
    val versionLine: String,
    val accentColor: Color,
    val containerColor: Color,
    val icon: ImageVector,
)

private fun softwareVersionText(): String = cachedSoftwareVersion

private val cachedSoftwareVersion: String by lazy(::readSoftwareVersion)

@SuppressLint("PrivateApi") // ColorOS-only display property; Build.DISPLAY is the safe fallback.
private fun readSoftwareVersion(): String = try {
    (Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java)
        .invoke(null, SOFTWARE_VERSION_PROPERTY) as? String)
        ?.trim()
        .orEmpty()
        .ifBlank { Build.DISPLAY.orEmpty() }
} catch (exception: Exception) {
    AppDiagnostics.logger.report(
        level = DiagnosticLevel.Warning,
        event = DiagnosticEvent.SystemInfoReadFailed,
        message = "Unable to read the ColorOS software version property",
        cause = exception,
        attributes = mapOf("scope" to "software_version"),
        occurrence = OccurrencePolicy.Once("software_version"),
    )
    Build.DISPLAY.orEmpty()
}

@Composable
private fun HomeScreenPreview(state: HomeScreenState) {
    val snackbarHostState = remember { SnackbarHostState() }
    HomeScreen(
        state = state,
        bottomPadding = 0.dp,
        snackbarHostState = snackbarHostState,
        onSyncRules = { _, _ -> },
        onRestartSystemUi = {},
    )
}

@Preview(
    name = "Home Screen Light",
    showBackground = true,
    widthDp = 393,
    heightDp = 852,
)
@Composable
private fun HomeScreenLightPreview() {
    C17NotifyReplaceTheme(darkTheme = false) {
        HomeScreenPreview(
            state = HomeScreenState(
                rulesCount = 128,
                enabledRulesCount = 96,
                rulesUpdatedAt = 1742861100000L,
                syncStage = RuleSyncStage.Idle,
            ),
        )
    }
}

@Preview(
    name = "Home Screen Dark",
    showBackground = true,
    widthDp = 393,
    heightDp = 852,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HomeScreenDarkPreview() {
    C17NotifyReplaceTheme(darkTheme = true) {
        HomeScreenPreview(
            state = HomeScreenState(
                rulesCount = 256,
                enabledRulesCount = 180,
                rulesUpdatedAt = 1742861400000L,
                syncStage = RuleSyncStage.MirroringRemote,
            ),
        )
    }
}

@Preview(
    name = "Home Screen Empty",
    showBackground = true,
    widthDp = 393,
    heightDp = 852,
)
@Composable
private fun HomeScreenEmptyPreview() {
    C17NotifyReplaceTheme(darkTheme = false) {
        HomeScreenPreview(
            state = HomeScreenState(
                rulesCount = 0,
                enabledRulesCount = 0,
                rulesUpdatedAt = 0L,
                syncStage = RuleSyncStage.Idle,
            ),
        )
    }
}
