package com.c17.notify.replace.hook.systemui

import android.os.Handler
import android.os.Looper
import com.c17.notify.replace.diagnostics.DiagnosticEvent
import com.c17.notify.replace.diagnostics.DiagnosticLevel
import com.c17.notify.replace.diagnostics.Diagnostics
import com.c17.notify.replace.diagnostics.OccurrencePolicy
import com.c17.notify.replace.hook.icon.ThemeIconProvider
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the module from being the reason SystemUI runs out of heap, and leaves a trace when the
 * host is the one filling it.
 *
 * On 2026-11-10 16:10 the host died with `java.lang.OutOfMemoryError` on four threads at once —
 * 40, 64, 16 and 56 byte allocations, "giving up on allocation because <1% of heap free after GC",
 * 512 MB target footprint — after roughly 24 hours of uptime. The phone stopped responding until
 * the system rebooted, and the first notification after that boot showed the raw app icon, which
 * reads exactly like a module bug. Nothing in that log says what filled the heap, so this watchdog
 * samples the process heap once a minute and, from [PERCENT_THRESHOLD] on, drops the module's own
 * caches and reports the numbers at Error level — the only level the release build keeps.
 */
internal object SystemUiHeapWatchdog {
    private const val INTERVAL_MILLIS = 60_000L

    /** Where a line is worth writing: far enough above steady state to mean something. */
    private const val PERCENT_REPORT = 85

    /**
     * Where the module's own caches are dropped. Deliberately well above [PERCENT_REPORT] — the
     * theme bitmap cache exists because a cold resolve costs 22-60 ms on the main thread, so it must
     * not be evicted just because the host is a bit full.
     */
    private const val PERCENT_TRIM = 92

    private const val MEBIBYTE = 1024L * 1024L

    private val started = AtomicBoolean(false)

    fun start(diagnostics: Diagnostics, themeIcons: ThemeIconProvider) {
        if (!started.compareAndSet(false, true)) return
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                runCatching { sample(diagnostics, themeIcons) }
                handler.postDelayed(this, INTERVAL_MILLIS)
            }
        }
        handler.postDelayed(tick, INTERVAL_MILLIS)
    }

    private fun sample(diagnostics: Diagnostics, themeIcons: ThemeIconProvider) {
        val runtime = Runtime.getRuntime()
        val max = runtime.maxMemory()
        if (max <= 0L) return
        val used = runtime.totalMemory() - runtime.freeMemory()
        val percent = (used * 100 / max).toInt()
        if (percent < PERCENT_REPORT) return
        val trimmed = percent >= PERCENT_TRIM
        if (trimmed) {
            // Our own share first: a bitmap cache and two maps are all the module keeps per process,
            // and all of them rebuild from the host on demand.
            runCatching { themeIcons.clearCache() }
            StatusBarIconReplacementCache.clear()
        }
        diagnostics.report(
            level = DiagnosticLevel.Error,
            event = DiagnosticEvent.SystemUiHeapPressure,
            message = if (trimmed) {
                "SystemUI 堆占用 ${percent}%，已释放模块自身的图标缓存"
            } else {
                "SystemUI 堆占用 ${percent}%，模块自身缓存未释放（阈值 ${PERCENT_TRIM}%）"
            },
            attributes = mapOf(
                "usedMb" to used / MEBIBYTE,
                "maxMb" to max / MEBIBYTE,
                "percent" to percent,
                "trimmed" to trimmed.toString(),
            ),
            occurrence = OccurrencePolicy.RateLimited("systemui:heap_pressure", 10 * 60_000L),
        )
    }
}
