package com.c17.notify.replace.hook.systemui

import android.graphics.drawable.Icon

/**
 * Shares successful status-bar Icon replacements with AOD / lock-screen capsule
 * pipelines that never call IconManager.getIconDescriptor.
 *
 * Bounded on purpose. The key is the notification key, and every message of every conversation
 * brings its own one, so an unbounded map grows for the whole life of the SystemUI process. The
 * entries are only useful for the first frames after a notification is posted — anything older is
 * re-resolved by the normal path — so the most recent [MAX_ENTRIES] are kept and the rest evicted.
 */
internal object StatusBarIconReplacementCache {
    data class Entry(
        val icon: Icon,
        val isColorable: Boolean,
    )

    private const val MAX_ENTRIES = 128

    private val lock = Any()

    /** Access-ordered so [trim] drops the least recently used entry rather than a random one. */
    private val byKey = LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true)
    private val byPackage = LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true)

    fun put(notificationKey: String, packageName: String, icon: Icon, isColorable: Boolean) {
        val entry = Entry(icon, isColorable)
        synchronized(lock) {
            byKey[notificationKey] = entry
            byPackage[packageName] = entry
            trimToSize(byKey)
            trimToSize(byPackage)
        }
    }

    fun iconFor(notificationKey: String?, packageName: String?): Icon? =
        lookup(notificationKey, packageName)?.icon

    fun lookup(notificationKey: String?, packageName: String?): Entry? = synchronized(lock) {
        val exact = notificationKey?.let { byKey[it] }
        exact ?: packageName?.let { byPackage[it] }
    }

    fun clear() {
        synchronized(lock) {
            byKey.clear()
            byPackage.clear()
        }
    }

    private fun trimToSize(map: LinkedHashMap<String, Entry>) {
        while (map.size > MAX_ENTRIES) {
            val eldest = map.keys.firstOrNull() ?: return
            map.remove(eldest)
        }
    }
}
