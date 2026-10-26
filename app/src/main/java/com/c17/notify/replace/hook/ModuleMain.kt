package com.c17.notify.replace.hook

import android.content.SharedPreferences
import com.c17.notify.replace.BuildConfig
import com.c17.notify.replace.core.ModuleInfo
import com.c17.notify.replace.core.SystemPackages
import com.c17.notify.replace.diagnostics.DiagnosticEvent
import com.c17.notify.replace.diagnostics.DiagnosticLevel
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.diagnostics.OccurrencePolicy
import com.c17.notify.replace.hook.games.GameAssistantRuntime
import com.c17.notify.replace.hook.systemserver.SystemServerHooks
import com.c17.notify.replace.hook.systemui.SystemUiRuntime
import com.c17.notify.replace.rules.RuleStore
import com.highcapable.yukihookapi.YukiHook
import com.highcapable.yukihookapi.annotation.xposed.YukiHookLibXposedEntry
import com.highcapable.yukihookapi.hook.param.PackageParam
import com.highcapable.yukihookapi.hook.xposed.YukiHookXposedModule
import com.highcapable.yukihookapi.hook.xposed.bridge.event.registerFrameworkEvents
import com.highcapable.yukihookapi.hook.xposed.bridge.event.v101.onModuleLoaded

@YukiHookLibXposedEntry(
    minApiVersion = 102,
    targetApiVersion = 102,
    scope = ["system", SystemPackages.SYSTEM_UI, SystemPackages.GAME_ASSISTANT],
    staticScope = true,
)
object ModuleMain : YukiHookXposedModule {

    private var target = ProcessTarget.Ignore
    private var installed = false
    private lateinit var processName: String
    private lateinit var diagnostics: Diagnostics
    private lateinit var openConfigPreferences: () -> SharedPreferences

    override fun onInit() {
        YukiHook.configure {
            debug = BuildConfig.DEBUG
            dataChannel = false
            logging { tag = ModuleInfo.LOG_TAG }
        }
        registerFrameworkEvents {
            onModuleLoaded { param ->
                processName = param.processName
                target = resolveProcessTarget(param.isSystemServer, param.processName)
                diagnostics = Diagnostics(YukiHookDiagnosticSink(processName))
                // Native SharedPreferences keeps the revision listener our publish protocol relies
                // on, so the factory stays on the framework API instead of the generic wrapper.
                openConfigPreferences = { getRemotePreferences(RuleStore.GROUP_CONFIG) }

                diagnostics.report(
                    level = DiagnosticLevel.Info,
                    event = DiagnosticEvent.ModuleLoaded,
                    message = "模块入口已路由",
                    attributes = mapOf(
                        "api" to YukiHook.Status.Executor.apiVersion,
                        "framework" to YukiHook.Status.Executor.name,
                        "target" to target.diagnosticName,
                    ),
                    occurrence = OccurrencePolicy.Once(processName),
                )
                if (target == ProcessTarget.Ignore) {
                    diagnostics.debug(
                        event = DiagnosticEvent.ProcessIgnored,
                        message = "当前进程不承载任何模块功能",
                        attributes = emptyMap(),
                        occurrence = OccurrencePolicy.Once(processName),
                    )
                }
            }
        }
    }

    override fun onHook() = YukiHook.encase {
        loadSystem {
            if (target == ProcessTarget.SystemServer) installTarget()
        }
        loadApp(SystemPackages.SYSTEM_UI) {
            if (target == ProcessTarget.SystemUiMain && isFirstApplication) installTarget()
        }
        loadApp(SystemPackages.GAME_ASSISTANT) {
            if (target == ProcessTarget.GameAssistant && isFirstApplication) installTarget()
        }
    }

    private fun PackageParam.installTarget() {
        if (installed) return
        installed = true
        try {
            val loader = requireNotNull(hostClassLoader) { "Host ClassLoader is unavailable" }
            val hooks = HookRegistrar(this, diagnostics, processName)
            when (target) {
                ProcessTarget.SystemServer -> SystemServerHooks(
                    hooks = hooks,
                    diagnostics = diagnostics,
                ).install(loader)

                ProcessTarget.SystemUiMain -> SystemUiRuntime(
                    openConfigPreferences = openConfigPreferences,
                    sharedFiles = sharedFiles,
                    hooks = hooks,
                    diagnostics = diagnostics,
                    processName = processName,
                ).install(loader)

                ProcessTarget.GameAssistant -> GameAssistantRuntime(
                    hooks = hooks,
                    diagnostics = diagnostics,
                    processName = processName,
                ).install(loader)

                ProcessTarget.Ignore -> Unit
            }
        } catch (exception: Exception) {
            diagnostics.installationFailure(
                scope = "${target.diagnosticName}:initialize",
                message = "进程 Hook 初始化失败：${target.diagnosticName}",
                cause = exception,
            )
        }
        // The generated entry owns lifecycle dispatch. Hot reload stays disabled (the default):
        // our hooks, config listeners and delayed UI work have a process lifetime.
    }
}

internal enum class ProcessTarget(val diagnosticName: String) {
    SystemServer("system_server"),
    SystemUiMain("systemui_main"),
    GameAssistant("games_main"),
    Ignore("ignore"),
}

internal fun resolveProcessTarget(
    isSystemServer: Boolean,
    processName: String,
): ProcessTarget = when {
    isSystemServer -> ProcessTarget.SystemServer
    processName == SystemPackages.SYSTEM_UI -> ProcessTarget.SystemUiMain
    processName == SystemPackages.GAME_ASSISTANT -> ProcessTarget.GameAssistant
    else -> ProcessTarget.Ignore
}
