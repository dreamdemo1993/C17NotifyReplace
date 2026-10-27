package com.c17.notify.replace.hook.systemui

import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.c17.notify.replace.diagnostics.DiagnosticEvent
import com.c17.notify.replace.diagnostics.DiagnosticLevel
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.diagnostics.OccurrencePolicy
import com.c17.notify.replace.hook.HookRegistrar
import com.c17.notify.replace.hook.runtimeFailure
import java.util.concurrent.atomic.AtomicInteger

/**
 * ColorOS 17 流体云（Pantanal Seedling）。
 *
 * 状态栏胶囊、下拉栏顶部的流体云卡片**不是**普通的通知行：卡片数据由 `com.coloros.sceneservice`
 * 通过 CardServer 下发，SystemUI 里的 `PluginSeedling` 插件负责渲染（插件 APK 不在 SystemUI
 * dex 内，但加载进 SystemUI 进程）。因此通知行的图标接管链路（smallIcon / IconManager /
 * header icon）完全碰不到它们。
 *
 * 插件类不可静态分析，所以这里先做结构探测：容器 attach 后把插件 View 树（类名、资源 id、
 * 尺寸、ImageView 的 drawable）逐节点写进日志，用来定位"哪一个是 app 图标"。同时把
 * `SeedlingPluginCallUtils.getBundle(...)` 造出的通信 Bundle 的键打印出来，确认卡片数据里是否
 * 自带 packageName / 图标。
 */
internal class FluidCloudHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val members: FluidCloudMembers,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val attachProbes = AtomicInteger()
    private val bundleProbes = AtomicInteger()
    private val pluginProbes = AtomicInteger()

    fun install() {
        hooks.install(members.containerAttached, "systemui.fluid_cloud.container") { chain ->
            val result = chain.proceed()
            val container = chain.instanceOrNull as? ViewGroup ?: return@install result
            scheduleProbe(container)
            result
        }

        for (method in members.pluginBundles) {
            hooks.install(method, "systemui.fluid_cloud.plugin_bundle") { chain ->
                val result = chain.proceed()
                (result as? Bundle)?.let(::probeBundle)
                result
            }
        }

        members.pluginAdded?.let { method ->
            hooks.install(method, "systemui.fluid_cloud.plugin_loaded") { chain ->
                val result = chain.proceed()
                probePlugin(chain.args)
                result
            }
        }
    }

    /**
     * 插件 APK 不在 SystemUI dex 内，只能从实例的 ClassLoader 反推它的 dex 路径；这条日志决定
     * 后续能否直接静态分析插件布局。
     */
    private fun probePlugin(args: Array<Any?>) {
        if (pluginProbes.incrementAndGet() > MAX_PLUGIN_PROBES) return
        try {
            // onPluginAdded(SeedlingPlugin, Context)：第一个参数就是插件实例。
            val plugin = args.getOrNull(0)
            val loader = plugin?.javaClass?.classLoader
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.FluidCloudProbe,
                message = "流体云插件已加载",
                attributes = mapOf(
                    "plugin" to (plugin?.javaClass?.name ?: "null"),
                    "loader" to (loader?.javaClass?.name ?: "null"),
                    "dex" to classLoaderPaths(loader),
                ),
                occurrence = OccurrencePolicy.Always,
            )
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fluid_cloud:probe_plugin",
                message = "流体云插件信息探测失败",
                cause = exception,
            )
        }
    }

    private fun classLoaderPaths(loader: ClassLoader?): String {
        if (loader == null) return "null"
        return try {
            val pathList = Class.forName("dalvik.system.BaseDexClassLoader")
                .getDeclaredField("pathList")
                .apply { isAccessible = true }
                .get(loader) ?: return "null"
            val elements = pathList.javaClass
                .getDeclaredField("dexElements")
                .apply { isAccessible = true }
                .get(pathList) as? Array<*> ?: return "null"
            elements.mapNotNull { element ->
                element?.javaClass?.getDeclaredField("path")
                    ?.apply { isAccessible = true }
                    ?.get(element) as? String
            }.distinct().joinToString(",")
        } catch (exception: Exception) {
            "error:${exception.javaClass.simpleName}"
        }
    }

    private fun scheduleProbe(container: ViewGroup) {
        if (attachProbes.incrementAndGet() > MAX_CONTAINER_PROBES) return
        for (delay in PROBE_DELAYS_MILLIS) {
            mainHandler.postDelayed({ dumpTree(container, delay) }, delay)
        }
    }

    private fun dumpTree(container: ViewGroup, delay: Long) {
        try {
            val nodes = ArrayList<String>(MAX_NODES)
            collect(container, 0, nodes)
            val label = "${container.javaClass.simpleName}@${delay}ms"
            nodes.forEachIndexed { index, node ->
                diagnostics.report(
                    level = DiagnosticLevel.Debug,
                    event = DiagnosticEvent.FluidCloudProbe,
                    message = "流体云视图树[$label] $node",
                    attributes = mapOf(
                        "node" to node,
                        "index" to index,
                        "delay" to delay,
                    ),
                    occurrence = OccurrencePolicy.Always,
                )
            }
            diagnostics.report(
                level = DiagnosticLevel.Debug,
                event = DiagnosticEvent.FluidCloudProbe,
                message = "流体云视图树[$label] 共 ${nodes.size} 个节点",
                attributes = mapOf(
                    "nodes" to nodes.size,
                    "child_count" to container.childCount,
                    "delay" to delay,
                ),
                occurrence = OccurrencePolicy.Always,
            )
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fluid_cloud:probe",
                message = "流体云视图树探测失败",
                cause = exception,
            )
        }
    }

    private fun collect(view: View, depth: Int, out: MutableList<String>) {
        if (out.size >= MAX_NODES) return
        out += describe(view, depth)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collect(view.getChildAt(index), depth + 1, out)
            }
        }
    }

    private fun describe(view: View, depth: Int): String = buildString {
        append(depth).append('|')
        append(view.javaClass.simpleName)
        val id = view.id
        if (id != View.NO_ID) {
            append('#').append(resourceName(view, id))
        }
        append(' ').append(view.width).append('x').append(view.height)
        if (view.visibility != View.VISIBLE) append(" vis=").append(view.visibility)
        when (view) {
            is ImageView -> {
                val drawable = view.drawable
                append(" drawable=").append(drawable?.javaClass?.simpleName ?: "null")
                if (drawable != null) {
                    append('(').append(drawable.intrinsicWidth).append('x')
                    append(drawable.intrinsicHeight).append(')')
                }
                if (drawable is BitmapDrawable) {
                    append(" bmp=").append(drawable.bitmap?.width).append('x')
                    append(drawable.bitmap?.height)
                }
            }

            is TextView -> {
                val text = view.text?.toString()?.replace('\n', ' ')
                if (!text.isNullOrEmpty()) append(" text=").append(text.take(MAX_TEXT_LENGTH))
            }
        }
    }

    private fun resourceName(view: View, id: Int): String = try {
        view.resources.getResourceEntryName(id)
    } catch (_: Exception) {
        id.toString()
    }

    private fun probeBundle(bundle: Bundle) {
        if (bundleProbes.incrementAndGet() > MAX_BUNDLE_PROBES) return
        try {
            for (key in bundle.keySet().sorted()) {
                with(bundle.get(key)) {
                    diagnostics.report(
                        level = DiagnosticLevel.Debug,
                        event = DiagnosticEvent.FluidCloudProbe,
                        message = "流体云插件数据键 $key",
                        attributes = mapOf(
                            "key" to key,
                            "type" to (this?.javaClass?.simpleName ?: "null"),
                            "value" to (this?.toString()?.take(MAX_VALUE_LENGTH)),
                        ),
                        occurrence = OccurrencePolicy.Always,
                    )
                }
            }
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fluid_cloud:probe_bundle",
                message = "流体云插件数据探测失败",
                cause = exception,
            )
        }
    }

    private companion object {
        /** 插件把 View 加进容器、卡片渲染完成各有延迟，两个时间点各拍一次。 */
        val PROBE_DELAYS_MILLIS = longArrayOf(600L, 1_600L)

        const val MAX_CONTAINER_PROBES = 6
        const val MAX_BUNDLE_PROBES = 12
        const val MAX_PLUGIN_PROBES = 4
        const val MAX_NODES = 60
        const val MAX_TEXT_LENGTH = 16
        const val MAX_VALUE_LENGTH = 80
    }
}
