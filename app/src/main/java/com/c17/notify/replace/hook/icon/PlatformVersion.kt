package com.c17.notify.replace.hook.icon

/**
 * The vendor release the host process is running on.
 *
 * ColorOS' own version is not part of `Build.VERSION`: `ro.build.version.oplusrom` carries it
 * (`V17.0.1`, `V16.0.2`, …). Anything unreadable counts as "unknown", which keeps every
 * version-gated decision on the conservative side.
 */
internal object PlatformVersion {

    /**
     * ColorOS 17 repaints the notification shade as liquid glass. The dark neutral
     * (`0xFF707173`) the panels used in light mode disappears against that backdrop, so the
     * night colour is used in both modes from 17 on — the same rule the upstream project applies.
     */
    val isColorOS17OrNewer: Boolean by lazy { colorOsMajorVersion >= COLOR_OS_17 }

    private val colorOsMajorVersion: Int by lazy {
        readSystemProperty(OPLUS_ROM_VERSION)
            .removePrefix("V")
            .substringBefore(".")
            .toIntOrNull()
            ?: 0
    }

    private fun readSystemProperty(name: String): String = try {
        // Hidden API, but readable: SystemProperties.get is used by every vendor component.
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, name) as? String
            ?: ""
    } catch (_: Throwable) {
        ""
    }

    private const val OPLUS_ROM_VERSION = "ro.build.version.oplusrom"
    private const val COLOR_OS_17 = 17
}
