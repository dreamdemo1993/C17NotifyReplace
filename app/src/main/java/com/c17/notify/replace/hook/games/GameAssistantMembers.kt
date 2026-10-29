package com.c17.notify.replace.hook.games

import android.graphics.drawable.Drawable
import com.c17.notify.replace.hook.reflect.Reflection
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** One method the module needs to hook, resolved at runtime. */
internal class HookTarget(val owner: Class<*>, val method: Method) {

    val label: String = owner.name + "#" + method.name
}

/**
 * Locates the assistant's hook targets by *shape* instead of by a name.
 *
 * 10.43.7 enabled R8 for the `business.*` packages, and every anchor the module used was gone:
 *
 * - `ExternalApplicationManager` → `business.secondarypanel.manager.a`;
 * - `ExternalApplicationManager#u(String)` → that class' `f(String)`;
 * - `GameAppCellView#c(model, position)` → `V`;
 * - `MessageNotificationBoxManager#Y(ImageView, MessageData)` → gone entirely; the message box now
 *   binds through the commonui multitype adapter and builds its mark from a bundled resource, so
 *   the mark is replaced from the row's views instead — see `GameAssistantRuntime`.
 *
 * A name that only lives until the next build is not an anchor — the signature is. Each lookup
 * below walks the class names R8 hands out (`a`, `b`, … in the original package, the legacy name
 * first) and keeps the first method whose parameters and return type match.
 */
internal object GameAssistantMembers {

    /** Package that holds `ExternalApplicationManager` and friends. */
    const val EDGE_PANEL_PACKAGE = "business.secondarypanel.manager"

    private const val LEGACY_EDGE_MANAGER = "ExternalApplicationManager"

    /** The game dock's `Drawable` factory: `u(String)` before 10.43.7, `f(String)` after. */
    fun edgePanelIconLoader(classLoader: ClassLoader): HookTarget? =
        firstInPackage(classLoader, EDGE_PANEL_PACKAGE, LEGACY_EDGE_MANAGER) { method ->
            method.parameterCount == 1 &&
                method.parameterTypes[0] == String::class.java &&
                Drawable::class.java.isAssignableFrom(method.returnType)
        }

    /**
     * `GameAppCellView`'s bind method: `(model, position)`. The model type is obfuscated and the
     * name changes every release, but the shape — one app class plus an `int` position, returning
     * nothing — does not.
     */
    fun cellBind(cell: Class<*>): Method? = cell.declaredMethods.firstOrNull { method ->
        method.parameterCount == 2 &&
            method.parameterTypes[1] == Int::class.javaPrimitiveType &&
            method.returnType == Void.TYPE &&
            !Modifier.isStatic(method.modifiers) &&
            !method.isSynthetic &&
            !method.parameterTypes[0].name.startsWith("android.")
    }

    private fun firstInPackage(
        classLoader: ClassLoader,
        packageName: String,
        legacyName: String,
        predicate: (Method) -> Boolean,
    ): HookTarget? {
        val legacy = load(classLoader, "$packageName.$legacyName")
        if (legacy != null) {
            val method = legacy.find(predicate)
            if (method != null) return HookTarget(legacy, method)
        }
        for (name in OBFUSCATED_NAMES) {
            val owner = load(classLoader, "$packageName.$name") ?: continue
            val method = owner.find(predicate) ?: continue
            return HookTarget(owner, method)
        }
        return null
    }

    private fun Class<*>.find(predicate: (Method) -> Boolean): Method? =
        declaredMethods.firstOrNull { method ->
            !Modifier.isStatic(method.modifiers) &&
                !method.isSynthetic &&
                predicate(method)
        }

    private fun load(classLoader: ClassLoader, name: String): Class<*>? =
        Reflection.loadClassOrNull(name, classLoader) { }

    /** R8's per-package name pool: `a`, `b`, … */
    private val OBFUSCATED_NAMES: List<String> = ('a'..'z').map { it.toString() }
}
