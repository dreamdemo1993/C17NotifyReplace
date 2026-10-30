package com.c17.notify.replace.hook

import com.c17.notify.replace.core.ModuleInfo
import com.c17.notify.replace.diagnostics.DiagnosticEvent
import com.c17.notify.replace.diagnostics.DiagnosticLevel
import com.c17.notify.replace.diagnostics.DiagnosticRecord
import com.c17.notify.replace.diagnostics.DiagnosticSink
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.diagnostics.OccurrencePolicy
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.param.HookChain
import com.highcapable.yukihookapi.hook.param.PackageParam
import java.lang.reflect.Executable

internal class YukiHookDiagnosticSink(
    private val processName: String,
) : DiagnosticSink {
    override fun write(record: DiagnosticRecord) {
        // The formal release stays quiet for routine noise: only genuine runtime failures (Error)
        // and the explicitly traced icon decisions are written. Everything else (hook installed,
        // config published, per-notification debug chatter) remains source-build only.
        val traced = record.event in TRACED_EVENTS
        if (record.level != DiagnosticLevel.Error && !traced) return
        val contextualRecord = record.copy(
            attributes = mapOf("process" to processName) + record.attributes,
        )
        val message = contextualRecord.render()
        val cause = contextualRecord.cause
        when (contextualRecord.level) {
            DiagnosticLevel.Debug -> YLog.debug(message, cause, ModuleInfo.LOG_TAG, YLog.EnvType.XPOSED)
            DiagnosticLevel.Info -> YLog.info(message, cause, ModuleInfo.LOG_TAG, YLog.EnvType.XPOSED)
            DiagnosticLevel.Warning -> YLog.warn(message, cause, ModuleInfo.LOG_TAG, YLog.EnvType.XPOSED)
            DiagnosticLevel.Error -> YLog.error(message, cause, ModuleInfo.LOG_TAG, YLog.EnvType.XPOSED)
        }
    }
}

internal class HookRegistrar(
    private val packageParam: PackageParam,
    private val diagnostics: Diagnostics,
    private val processName: String,
) {
    /**
     * Installs one independently degradable hook.
     *
     * YukiHook keeps a failing callback from breaking the host: the invocation continues as if the
     * hook did not exist, and the failure lands in [onCallbackFailure] below.
     */
    fun install(
        executable: Executable,
        id: String,
        interceptor: (HookChain) -> Any?,
    ) {
        try {
            with(packageParam) {
                executable.intercept { interceptor(this) }
                    .onCallbackFailure { _, cause ->
                        diagnostics.runtimeFailure(
                            scope = "$processName:$id",
                            message = "Hook 回调失败，交回宿主调用链",
                            cause = cause,
                        )
                    }
            }
            diagnostics.debug(
                event = DiagnosticEvent.HookInstalled,
                message = "Hook 已安装",
                attributes = attributes(id),
                occurrence = OccurrencePolicy.Once("$processName:$id"),
            )
        } catch (exception: Exception) {
            diagnostics.installationFailure(
                scope = "$processName:$id",
                message = "Hook 初始化失败",
                cause = exception,
            )
        }
    }

    private fun attributes(id: String) = mapOf(
        "hook" to id,
    )
}

internal fun Diagnostics.memberMissing(
    scope: String,
    message: String,
    cause: Throwable? = null,
) {
    report(
        level = DiagnosticLevel.Warning,
        event = DiagnosticEvent.MemberMissing,
        message = message,
        cause = cause,
        attributes = mapOf("scope" to scope),
        occurrence = OccurrencePolicy.Once(scope),
    )
}

internal fun Diagnostics.runtimeFailure(
    scope: String,
    message: String,
    cause: Throwable,
    revision: Long? = null,
) {
    report(
        level = DiagnosticLevel.Error,
        event = DiagnosticEvent.HookRuntimeFailed,
        message = message,
        cause = cause,
        attributes = buildMap {
            put("scope", scope)
            revision?.let { put("revision", it) }
        },
        occurrence = OccurrencePolicy.RateLimited(scope, RUNTIME_LOG_INTERVAL_MILLIS),
    )
}

internal fun Diagnostics.installationFailure(
    scope: String,
    message: String,
    cause: Exception,
) {
    report(
        level = DiagnosticLevel.Error,
        event = DiagnosticEvent.HookInstallFailed,
        message = message,
        cause = cause,
        attributes = mapOf("scope" to scope),
        occurrence = OccurrencePolicy.Once(scope),
    )
}

private const val RUNTIME_LOG_INTERVAL_MILLIS = 60_000L

/**
 * Icon decisions worth one line in a user-submitted LSPosed log even on a release build: they are
 * already rate-limited at the call site and answer the usual question "why is this row not themed".
 */
private val TRACED_EVENTS = setOf(
    DiagnosticEvent.HostAvatarYield,
    DiagnosticEvent.HostAvatarReplace,
    DiagnosticEvent.IconReapplied,
    DiagnosticEvent.PackageIconResolved,
    // Rate-limited to one line per reason per minute, and the only record that tells "the row was
    // never reached" apart from "we wrote and the host took the icon back".
    DiagnosticEvent.PanelRowSkipped,
    DiagnosticEvent.GameAssistantIconReplaced,
    DiagnosticEvent.GameAssistantIconSkipped,
    DiagnosticEvent.GameAssistantIconSelfCheck,
    DiagnosticEvent.GameAssistantHookResolved,
    DiagnosticEvent.ThemeIconBlank,
)
