package com.mukapp.mote.data

import android.content.SharedPreferences
import com.mukapp.mote.data.model.ApiSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ApiSettingsStoreTest {

    @Test
    fun saveAndLoadRoundTripsCompressionSettings() {
        val preferences = InMemorySharedPreferences()
        val settings = ApiSettings(
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            model = "chat-model",
            titleModel = "title-model",
            compressionModel = "summary-model",
            modelContextLength = 128_000,
            compressionTriggerLength = 96_000,
            searxngUrl = "https://search.example.com",
            reasoningEffort = "medium"
        )

        ApiSettingsStore.save(preferences, settings)

        assertEquals(settings, ApiSettingsStore.load(preferences))
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

        override fun getString(key: String?, defValue: String?): String? {
            return values[key] as? String ?: defValue
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int {
            return values[key] as? Int ?: defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            return values[key] as? Long ?: defValue
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            return values[key] as? Float ?: defValue
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return values[key] as? Boolean ?: defValue
        }

        override fun contains(key: String?): Boolean {
            return values.containsKey(key)
        }

        override fun edit(): SharedPreferences.Editor {
            return Editor()
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                key?.let { pending[it] = value }
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                key?.let { pending[it] = values?.toSet() }
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                key?.let { pending[it] = value }
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                key?.let { pending[it] = value }
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                key?.let { pending[it] = value }
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                key?.let { pending[it] = value }
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                key?.let { removals += it }
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearRequested = true
            }

            override fun commit(): Boolean {
                if (clearRequested) {
                    values.clear()
                }
                removals.forEach { key -> values.remove(key) }
                values.putAll(pending)
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
