package com.c17.notify.replace

import com.highcapable.yukihookapi.hook.xposed.application.ModuleApplication
import com.c17.notify.replace.framework.RemoteConfigCoordinator
import com.c17.notify.replace.framework.XposedServiceBridge
import com.c17.notify.replace.rules.RuleStore
import com.c17.notify.replace.ui.theme.applyPredictiveBackCallbackEnabled
import com.c17.notify.replace.ui.theme.readThemeConfig
import io.github.libxposed.service.XposedService

class C17NotifyReplaceApplication : ModuleApplication() {

    private val configPublisher = object : XposedServiceBridge.Listener {
        override fun onServiceChanged(service: XposedService?) {
            service?.let { RemoteConfigCoordinator.publish(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        applyPredictiveBackCallbackEnabled(
            context = this,
            enabled = readThemeConfig(this).predictiveBackToHomeEnabled,
        )
        RuleStore.initialize(this)
        XposedServiceBridge.initialize()
        XposedServiceBridge.addListener(configPublisher)
    }
}
