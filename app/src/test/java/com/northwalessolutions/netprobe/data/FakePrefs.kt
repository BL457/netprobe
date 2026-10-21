package com.northwalessolutions.netprobe.data

import android.content.SharedPreferences

/** An in-memory SharedPreferences for JVM tests; [edits] counts edit() calls so a test can prove a reset is one edit. */
class FakePrefs : SharedPreferences {
    val stored = HashMap<String, Any?>()
    var edits = 0
        private set

    override fun getAll(): MutableMap<String, *> = HashMap(stored)
    override fun getString(key: String, defValue: String?): String? = stored[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = (stored[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = stored[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = stored[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = stored[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = stored[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = stored.containsKey(key)
    override fun edit(): SharedPreferences.Editor {
        edits++
        return Editor()
    }
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private inner class Editor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clearAll = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values?.toSet(); return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { removed += key; return this }
        override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
        override fun commit(): Boolean {
            if (clearAll) stored.clear()
            removed.forEach { stored.remove(it) }
            pending.forEach { (k, v) -> if (v == null) stored.remove(k) else stored[k] = v }
            return true
        }
        override fun apply() { commit() }
    }
}
