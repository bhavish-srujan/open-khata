package com.example.khatabook

import android.content.SharedPreferences

/**
 * Deterministic in-memory [SharedPreferences], for JVM unit tests only.
 *
 * KhatabookRepository's production persistence is EncryptedSharedPreferences,
 * which needs a real AndroidKeyStore — unavailable in the plain JVM test
 * sandbox. KhatabookRepository takes a `prefsFactory` constructor parameter
 * precisely so tests can substitute this instead: a real, working
 * implementation of the same interface, backed by a plain in-memory map, so
 * repository logic (save/load, business switching, outbox persistence,
 * rollback, reconciliation) runs for real rather than being mocked away.
 *
 * Not a mock: reads reflect prior writes exactly as a real SharedPreferences
 * would, including across `edit()...apply()` cycles.
 */
class FakeSharedPreferences : SharedPreferences {

    private val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        if (data.containsKey(key)) data[key] as? String else defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        if (data.containsKey(key)) (data[key] as? Set<String>)?.toMutableSet() else defValues

    override fun getInt(key: String?, defValue: Int): Int =
        if (data.containsKey(key)) data[key] as? Int ?: defValue else defValue

    override fun getLong(key: String?, defValue: Long): Long =
        if (data.containsKey(key)) data[key] as? Long ?: defValue else defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        if (data.containsKey(key)) data[key] as? Float ?: defValue else defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        if (data.containsKey(key)) data[key] as? Boolean ?: defValue else defValue

    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) { /* no listeners are exercised by the repository under test */ }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) { /* no listeners are exercised by the repository under test */ }

    /**
     * Batches edits and only applies them on commit()/apply(), matching real
     * SharedPreferences.Editor semantics — including that clear() followed by
     * puts in the same editor session keeps those puts (clear only wipes
     * whatever existed *before* this editor).
     */
    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val toRemove = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            key?.let { pending[it] = values?.toSet() }
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            key?.let { pending[it] = value }
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { toRemove.add(it) }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) data.clear()
            toRemove.forEach { data.remove(it) }
            data.putAll(pending)
        }
    }
}
