package com.c17.notify.replace.hook.systemui

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ColorOS 17 流体云（Pantanal Seedling）的应用图标接管。
 *
 * 状态栏胶囊和下拉栏顶部的流体云卡片**不是**通知行：卡片数据由 `com.coloros.sceneservice`
 * 经 CardServer 下发，SystemUI 里的 `PluginSeedling` 插件负责渲染（插件 APK 在
 * `OPlusPluginClassLoader` 里，运行在 SystemUI 进程内）。所以通知行的 smallIcon / IconManager /
 * 聚合行那一整套链路完全碰不到它们，只能直接改插件渲染出来的 ImageView。
 *
 * 插件侧的实测结构（ColorOS 17，`re.9` / `re.10` 两天日志确认）：
 * - 胶囊 `CapsuleView`：`#capsule > left_section/right_section > ... > CustomLottieView#img_primary`
 * - 卡片 `CardView`：`#section_info > ... > FrameLayout#icon_container > CustomLottieView#img_primary`
 * - 媒体卡：`#header_container > OplusLottieAnimationView#img_top_right_icon`（真图在位图子 View 上）
 *
 * 图标控件本身不带包名，包名只存在于同一棵 View 上挂的模型对象里，形如
 * `CapsuleModel(key='0|com.jingdong.app.mall|935079901|null|10489')` /
 * `ViewItem: {key: 0|<pkg>|...}`，所以包名从模型 toString 里的 key 解析。
 * 图标控件是 `android.widget.ImageView` 子类（`CustomLottieView < com.airbnb.lottie.a <
 * ... < ImageView`），因此可以像普通 ImageView 一样 `setImageDrawable`。
 */
internal class FluidCloudHooks(
    private val hooks: HookRegistrar,
    private val diagnostics: Diagnostics,
    private val configuration: SystemUiConfiguration,
    private val members: FluidCloudMembers,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val attachProbes = AtomicInteger()
    private val bundleProbes = AtomicInteger()
    private val pluginProbes = AtomicInteger()
    private val cardProbes = AtomicInteger()

    /** 已经写过日志 / 已经排过替换任务的 View 实例，避免同一条卡片反复处理。 */
    private val loggedViews = Collections.synchronizedSet(HashSet<Int>())
    private val scheduledRoots = Collections.synchronizedSet(HashSet<Int>())
    private val modelDumped = Collections.synchronizedSet(HashSet<Int>())

    /** 图标 View → 它所属应用。插件重新绑定图标时靠这张表把主题图标再贴回去。 */
    private val iconPackages = Collections.synchronizedMap(WeakHashMap<View, String>())

    /** View → 从它身上挂的模型里解析出来的包名（可能多个，双卡胶囊就是两个）。 */
    private val viewPackages = Collections.synchronizedMap(WeakHashMap<View, List<String>>())

    /** 图标控件类的 `setImageDrawable` 只挂一次。 */
    private val hookedClasses = Collections.synchronizedSet(HashSet<String>())

    private val replacementCache = HashMap<String, Drawable>()
    private var replacementRevision = Long.MIN_VALUE

    /** 自己调用 `setImageDrawable` 时不要被自己的 hook 再拦一次。 */
    private val applying = ThreadLocal.withInitial { false }

    fun install() {
        hooks.install(members.containerAttached, "systemui.fluid_cloud.container") { chain ->
            val result = chain.proceed()
            val container = chain.instanceOrNull as? ViewGroup
            if (container != null && attachProbes.incrementAndGet() <= MAX_CONTAINER_PROBES) {
                mainHandler.postDelayed({ dumpTree(container, "attach") }, PROBE_DELAY_MILLIS)
            }
            result
        }

        // 插件的卡片/胶囊是 addView 进宿主的，这里既能盯到新 View，也能顺手做一次图标替换。
        members.addView?.let { method ->
            hooks.install(method, "systemui.fluid_cloud.add_view") { chain ->
                val result = chain.proceed()
                val child = chain.args.getOrNull(0) as? View
                if (child != null) onPluginViewAdded(child)
                result
            }
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

        for (method in members.cardCallbacks) {
            hooks.install(method, "systemui.fluid_cloud.card_${method.name}") { chain ->
                val result = chain.proceed()
                probeCard(method.name, chain.args)
                result
            }
        }
    }

    // ------------------------------------------------------------------ 替换

    private fun onPluginViewAdded(child: View) {
        val name = child.javaClass.name
        if (PLUGIN_PACKAGE_PREFIXES.none { name.startsWith(it) }) return
        val identity = System.identityHashCode(child)
        if (loggedViews.add(identity)) {
            report("流体云插件 View 加入容器", mapOf("child" to name))
        }
        if (!scheduledRoots.add(identity)) return
        if (scheduledRoots.size > MAX_SCHEDULED_ROOTS) return
        // 图标的 drawable 往往在 addView 之后才贴上（插件异步渲染），排几次补丁比死等一个时机稳。
        for (delay in APPLY_DELAYS_MILLIS) {
            mainHandler.postDelayed({ applyIcons(child) }, delay)
        }
    }

    /**
     * 把这棵子树里的流体云图标换成主题图标。主题图标与下拉栏通知行用的是同一份资源，
     * 换成它之后胶囊/卡片和通知栏看起来才是一套。
     */
    private fun applyIcons(root: View) {
        try {
            val snapshot = configuration.snapshot
            if (!snapshot.resolver.panelThemeIconsEnabled()) return
            val icons = collectIconViews(root)
            if (icons.isEmpty()) return
            val context = root.context?.applicationContext ?: return
            for (icon in icons) {
                val packageName = packageFor(icon) ?: continue
                val drawable = replacementFor(context, packageName, snapshot) ?: continue
                iconPackages[icon] = packageName
                hookIconClass(icon)
                if (icon.drawable === drawable) continue
                setIconDrawable(icon, drawable)
                diagnostics.report(
                    level = DiagnosticLevel.Info,
                    event = DiagnosticEvent.PackageIconResolved,
                    message = "流体云图标已替换：$packageName",
                    attributes = mapOf(
                        "package" to packageName,
                        "id" to resourceName(icon, icon.id),
                        "root" to root.javaClass.simpleName,
                    ),
                    occurrence = OccurrencePolicy.Once("fluid_cloud:icon:$packageName"),
                )
            }
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fluid_cloud:apply",
                message = "流体云图标替换失败，保留插件原始图标",
                cause = exception,
            )
        }
    }

    private fun setIconDrawable(icon: ImageView, drawable: Drawable) {
        applying.set(true)
        try {
            for (target in iconTargets(icon)) {
                if (target.drawable === drawable) continue
                target.setImageDrawable(drawable)
            }
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fluid_cloud:set_drawable",
                message = "设置流体云图标失败，保留插件原始图标",
                cause = exception,
            )
        } finally {
            applying.set(false)
        }
    }

    /**
     * 媒体卡的 `img_top_right_icon` 是包了一层的 Lottie 容器，真图挂在它的位图子 View 上，
     * 所以这类控件连第一个 ImageView 子 View 一起写；其余（img_primary 等）只写自己。
     */
    private fun iconTargets(icon: ImageView): List<ImageView> {
        val idName = resourceName(icon, icon.id)
        if (idName !in NESTED_ICON_IDS || icon !is ViewGroup) return listOf(icon)
        val targets = ArrayList<ImageView>(2)
        targets += icon
        if (icon.childCount > 0) (icon.getChildAt(0) as? ImageView)?.let { targets += it }
        return targets
    }

    /**
     * 插件重新绑定卡片时会再调一次 `setImageDrawable`，把我们贴的图标盖回原图；挂住图标控件类
     * 的这个方法，之后每次重绑都把主题图标补上去。
     */
    private fun hookIconClass(icon: ImageView) {
        val clazz = icon.javaClass
        if (!hookedClasses.add(clazz.name)) return
        val method: Method = try {
            clazz.getMethod("setImageDrawable", Drawable::class.java)
        } catch (_: NoSuchMethodException) {
            return
        }
        hooks.install(method, "systemui.fluid_cloud.icon_drawable") { chain ->
            val result = chain.proceed()
            val view = chain.instanceOrNull as? ImageView
            if (view != null && applying.get() != true) {
                val incoming = chain.args.getOrNull(0)
                // 插件清空图标（传 null）时不要补：那是卡片正在收起，补上会闪出一个图标。
                if (incoming is Drawable) {
                    val packageName = iconPackages[view]
                    val context = view.context?.applicationContext
                    val drawable = if (packageName != null && context != null) {
                        replacementFor(context, packageName, configuration.snapshot)
                    } else {
                        null
                    }
                    if (drawable != null && incoming !== drawable) setIconDrawable(view, drawable)
                }
            }
            result
        }
    }

    private fun replacementFor(
        context: Context,
        packageName: String,
        snapshot: RuntimeSnapshot,
    ): Drawable? {
        synchronized(replacementCache) {
            if (replacementRevision != snapshot.revision) {
                replacementCache.clear()
                replacementRevision = snapshot.revision
            }
            replacementCache[packageName]?.let { return it }
        }
        val drawable = try {
            snapshot.resolver.resolveThemeDrawableByPackage(context, packageName)
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fluid_cloud:theme_icon",
                message = "流体云主题图标解析失败：$packageName",
                cause = exception,
                revision = snapshot.revision,
            )
            null
        } ?: return null
        synchronized(replacementCache) { replacementCache[packageName] = drawable }
        return drawable
    }

    // ------------------------------------------------------------------ 包名

    /** 从图标 View 往上找最近的、挂得住模型的那一层；双卡胶囊的左右图标按位置取对应包名。 */
    private fun packageFor(icon: View): String? {
        iconPackages[icon]?.let { return it }
        var current: View? = icon
        var depth = 0
        while (current != null && depth < MAX_ANCESTORS) {
            val packages = packagesOf(current)
            if (packages.isNotEmpty()) {
                val index = iconIndexIn(icon, current, packages.size)
                return packages.getOrElse(index) { packages.last() }
            }
            current = current.parent as? View
            depth++
        }
        return null
    }

    private fun iconIndexIn(icon: View, ancestor: View, size: Int): Int {
        if (size <= 1) return 0
        val index = collectIconViews(ancestor).indexOfFirst { it === icon }
        return if (index < 0) 0 else index.coerceAtMost(size - 1)
    }

    /** 模型里的包名只出现在 key 上：`0|com.jingdong.app.mall|935079901|null|10489`。 */
    private fun packagesOf(view: View): List<String> {
        viewPackages[view]?.let { return it }
        val result = LinkedHashSet<String>()
        try {
            harvestFromObject(view, 0, result)
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fluid_cloud:packages",
                message = "解析流体云卡片所属应用失败",
                cause = exception,
            )
        }
        val packages = result.toList()
        viewPackages[view] = packages
        return packages
    }

    private fun harvestFromObject(target: Any, depth: Int, out: MutableSet<String>) {
        if (depth > MAX_MODEL_DEPTH || out.size >= MAX_PACKAGES) return
        var clazz: Class<*>? = target.javaClass
        var level = 0
        while (clazz != null && level <= MAX_MODEL_SUPER) {
            if (isPlatformClass(clazz.name)) break
            for (field in clazz.declaredFields) {
                if (out.size >= MAX_PACKAGES) return
                if (Modifier.isStatic(field.modifiers)) continue
                if (field.type.isPrimitive) continue
                if (isPlatformClass(field.type.name)) continue
                val value = readField(field, target) ?: continue
                harvestValue(value, depth, out)
            }
            clazz = clazz.superclass
            level++
        }
    }

    private fun harvestValue(value: Any, depth: Int, out: MutableSet<String>) {
        when (value) {
            is String -> {
                collectPackagesFromText(value, out)
                return
            }

            is Collection<*> -> {
                if (depth >= MAX_MODEL_DEPTH) return
                for (element in value) element?.let { harvestValue(it, depth + 1, out) }
                return
            }

            is Map<*, *> -> {
                if (depth >= MAX_MODEL_DEPTH) return
                for (element in value.values) element?.let { harvestValue(it, depth + 1, out) }
                return
            }

            is Array<*> -> {
                if (depth >= MAX_MODEL_DEPTH) return
                for (element in value) element?.let { harvestValue(it, depth + 1, out) }
                return
            }
        }
        if (isPlatformClass(value.javaClass.name)) return
        val text = runCatching { value.toString() }.getOrNull()
        val before = out.size
        if (text != null) collectPackagesFromText(text, out)
        // 已经在这层拿到包名就不再往下钻，避免模型 ↔ 仓库互相引用绕圈。
        if (out.size > before || depth >= MAX_MODEL_DEPTH) return
        harvestFromObject(value, depth + 1, out)
    }

    private fun collectPackagesFromText(text: String, out: MutableSet<String>) {
        if (!text.contains('|')) return
        for (match in PACKAGE_IN_KEY.findAll(text)) {
            if (out.size >= MAX_PACKAGES) return
            out += match.groupValues[1]
        }
    }

    private fun readField(field: Field, target: Any): Any? = try {
        field.isAccessible = true
        field.get(target)
    } catch (_: Exception) {
        null
    }

    private fun isPlatformClass(name: String): Boolean = PLATFORM_PREFIXES.any { name.startsWith(it) }

    /** 图标控件：可见、且 id 是流体云约定的那几个名字。 */
    private fun collectIconViews(root: View): List<ImageView> {
        val result = ArrayList<ImageView>(2)
        fun walk(view: View, depth: Int) {
            if (depth > MAX_NODES || result.size >= MAX_ICONS) return
            if (view is ImageView && view.visibility == View.VISIBLE &&
                resourceName(view, view.id) in ICON_IDS
            ) {
                result += view
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index), depth + 1)
        }
        walk(root, 0)
        return result
    }

    // ------------------------------------------------------------------ 探测

    private fun probeCard(callback: String, args: List<Any?>) {
        if (cardProbes.incrementAndGet() > MAX_CARD_PROBES) return
        try {
            val card = args.getOrNull(1) ?: return
            val attributes = mutableMapOf<String, Any?>(
                "callback" to callback,
                "card_class" to card.javaClass.name,
            )
            for (getter in CARD_GETTERS) {
                val method = card.javaClass.declaredMethods.firstOrNull {
                    it.name.startsWith(getter) && it.parameterTypes.isEmpty()
                } ?: continue
                method.isAccessible = true
                attributes[getter] = try {
                    method.invoke(card)?.toString()?.take(MAX_VALUE_LENGTH)
                } catch (exception: Exception) {
                    "error:${exception.javaClass.simpleName}"
                }
            }
            report("流体云卡片生命周期 $callback", attributes)
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fluid_cloud:probe_card",
                message = "流体云卡片信息探测失败",
                cause = exception,
            )
        }
    }

    private fun probePlugin(args: List<Any?>) {
        if (pluginProbes.incrementAndGet() > MAX_PLUGIN_PROBES) return
        try {
            // onPluginAdded(SeedlingPlugin, ...)：第一个参数就是插件实例。
            val plugin = args.getOrNull(0)
            val loader = plugin?.javaClass?.classLoader
            report(
                "流体云插件已加载",
                mapOf(
                    "plugin" to (plugin?.javaClass?.name ?: "null"),
                    "loader" to (loader?.javaClass?.name ?: "null"),
                    "apk" to (loader?.toString()?.take(MAX_VALUE_LENGTH) ?: "null"),
                ),
            )
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fluid_cloud:probe_plugin",
                message = "流体云插件信息探测失败",
                cause = exception,
            )
        }
    }

    private fun dumpTree(root: View, source: String) {
        try {
            val nodes = ArrayList<String>(MAX_NODES)
            collect(root, 0, nodes)
            val label = "${root.javaClass.simpleName}/$source"
            nodes.forEachIndexed { index, node ->
                report("流体云视图树[$label] $node", mapOf("node" to node, "index" to index))
            }
            report(
                "流体云视图树[$label] 共 ${nodes.size} 个节点",
                mapOf("nodes" to nodes.size, "child_count" to (root as? ViewGroup)?.childCount),
            )
            if (modelDumped.add(System.identityHashCode(root)) &&
                root.javaClass.simpleName in MODEL_ROOT_NAMES
            ) {
                dumpModel(root)
            }
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fluid_cloud:probe",
                message = "流体云视图树探测失败",
                cause = exception,
            )
        }
    }

    /** 卡片上挂的模型是包名的来源，模型结构变了要靠这行日志重新定位。 */
    private fun dumpModel(root: View) {
        try {
            val packages = packagesOf(root)
            val icons = collectIconViews(root).map { "${resourceName(it, it.id)}=${it.javaClass.simpleName}" }
            report(
                "流体云卡片所属应用",
                mapOf(
                    "root" to root.javaClass.simpleName,
                    "packages" to packages.joinToString(","),
                    "count" to packages.size,
                    "icons" to icons.joinToString(","),
                ),
            )
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fluid_cloud:probe_model",
                message = "流体云卡片模型探测失败",
                cause = exception,
            )
        }
    }

    private fun collect(view: View, depth: Int, out: MutableList<String>) {
        if (out.size >= MAX_NODES) return
        out += describe(view, depth)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collect(view.getChildAt(index), depth + 1, out)
        }
    }

    private fun describe(view: View, depth: Int): String = buildString {
        append(depth).append('|')
        append(view.javaClass.simpleName)
        val id = view.id
        if (id != View.NO_ID) append('#').append(resourceName(view, id))
        append(' ').append(view.width).append('x').append(view.height)
        if (view.visibility != View.VISIBLE) append(" vis=").append(view.visibility)
        when (view) {
            is ImageView -> {
                val drawable = view.drawable
                append(" drawable=").append(drawable?.javaClass?.simpleName ?: "null")
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

    private fun probeBundle(bundle: Bundle) {
        if (bundleProbes.incrementAndGet() > MAX_BUNDLE_PROBES) return
        try {
            for (key in bundle.keySet().sorted()) {
                val value = bundle.get(key)
                report(
                    "流体云插件数据键 $key",
                    mapOf(
                        "key" to key,
                        "type" to (value?.javaClass?.simpleName ?: "null"),
                        "value" to value?.toString()?.take(MAX_VALUE_LENGTH),
                    ),
                )
            }
        } catch (exception: Exception) {
            diagnostics.runtimeFailure(
                scope = "fluid_cloud:probe_bundle",
                message = "流体云插件数据探测失败",
                cause = exception,
            )
        }
    }

    private fun report(message: String, attributes: Map<String, Any?>) {
        diagnostics.report(
            level = DiagnosticLevel.Debug,
            event = DiagnosticEvent.FluidCloudProbe,
            message = message,
            attributes = attributes,
            occurrence = OccurrencePolicy.Always,
        )
    }

    private fun resourceName(view: View, id: Int): String = try {
        view.resources.getResourceEntryName(id)
    } catch (_: Exception) {
        id.toString()
    }

    private companion object {
        /** 插件类名前缀：只有插件自己的 View 才值得处理。 */
        val PLUGIN_PACKAGE_PREFIXES = listOf("com.oplus.systemui.plugins.seedling")

        /** 流体云里代表"应用图标"的资源 id。 */
        val ICON_IDS =
            setOf("img_primary", "img_secondary", "img_top_right_icon", "img_top_right_Immersive_icon")

        /** 这几类图标的真图挂在子 View 上，要连子 View 一起写。 */
        val NESTED_ICON_IDS = setOf("img_top_right_icon", "img_top_right_Immersive_icon")

        /** 只有卡片/胶囊根才值得拍整棵树和模型。 */
        val MODEL_ROOT_NAMES = setOf("CardView", "CapsuleView")

        /** 卡片模型的 key：`0|<pkg>|<id>|null|<n>`，包名一定带点。 */
        val PACKAGE_IN_KEY = Regex("""\|([A-Za-z][A-Za-z0-9_]*\.(?:[A-Za-z0-9_]+\.)*[A-Za-z0-9_]+)\|""")

        val PLATFORM_PREFIXES = listOf(
            "android.", "androidx.", "java.", "kotlin.", "kotlinx.", "com.airbnb.lottie.", "dalvik.",
        )

        /** 补丁时间点：插件渲染、重新绑定、动画结束各有延迟，多打几次。 */
        val APPLY_DELAYS_MILLIS = longArrayOf(150L, 500L, 1_200L, 2_400L)

        const val PROBE_DELAY_MILLIS = 600L
        const val MAX_ANCESTORS = 10
        const val MAX_CONTAINER_PROBES = 4
        const val MAX_BUNDLE_PROBES = 8
        const val MAX_PLUGIN_PROBES = 2
        const val MAX_CARD_PROBES = 10
        const val MAX_SCHEDULED_ROOTS = 160
        const val MAX_NODES = 60
        const val MAX_ICONS = 3
        const val MAX_PACKAGES = 4
        const val MAX_MODEL_DEPTH = 3
        const val MAX_MODEL_SUPER = 3
        const val MAX_TEXT_LENGTH = 16
        const val MAX_VALUE_LENGTH = 120

        /** `SeedlingCard` 上的关键 getter（部分带 `$seedling_...` 后缀，按前缀匹配）。 */
        val CARD_GETTERS =
            listOf("getClientName", "getServiceId", "getWidgetCode", "getHost", "getPageId")
    }
}
