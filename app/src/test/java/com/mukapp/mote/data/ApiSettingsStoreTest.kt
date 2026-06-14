package com.mukapp.mote.data

import android.content.SharedPreferences
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ModelInfo
import com.mukapp.mote.data.model.ModelProvider
import com.mukapp.mote.data.model.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiSettingsStoreTest {

    @Test
    fun saveAndLoadRoundTripsProviders() {
        val preferences = InMemorySharedPreferences()
        val provider = ModelProvider(
            id = "provider-1",
            name = "OpenAI",
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            models = listOf(
                ModelInfo(id = "chat-model", contextLength = 128_000, reasoningEffort = "high"),
                ModelInfo(id = "title-model", displayName = "标题", reasoningEffort = "low")
            )
        )
        val settings = ApiSettings(
            providers = listOf(provider),
            chatModel = ModelRef("provider-1", "chat-model"),
            titleModel = ModelRef("provider-1", "title-model"),
            compressionModel = ModelRef("provider-1", "chat-model"),
            compressionTriggerPercent = 70,
            searxngUrl = "https://search.example.com",
            tavilyApiKey = ""
        )

        ApiSettingsStore.save(preferences, settings)

        assertEquals(settings, ApiSettingsStore.load(preferences))
    }

    @Test
    fun migratesLegacySingleProviderSettings() {
        val preferences = InMemorySharedPreferences()
        preferences.edit()
            .putString("base_url", "https://api.example.com/v1")
            .putString("api_key", "sk-legacy")
            .putString("model", "gpt-4o")
            .putString("title_model", "gpt-4o-mini")
            .putString("compression_model", "gpt-4o")
            .putInt("model_context_length", 100_000)
            .putInt("compression_trigger_length", 80_000)
            .putString("reasoning_effort", "medium")
            .putString("searxng_url", "https://search.example.com")
            .commit()

        val migrated = ApiSettingsStore.load(preferences)

        assertEquals(1, migrated.providers.size)
        val provider = migrated.providers.first()
        assertEquals("https://api.example.com/v1", provider.baseUrl)
        assertEquals("sk-legacy", provider.apiKey)
        // gpt-4o 与 gpt-4o-mini 两个模型（compression 复用 gpt-4o）
        assertEquals(2, provider.models.size)
        val chat = provider.models.first { it.id == "gpt-4o" }
        assertEquals(100_000, chat.contextLength)
        assertEquals("medium", chat.reasoningEffort)
        assertNotNull(migrated.chatModel)
        assertEquals("gpt-4o", migrated.chatModel?.modelId)
        assertEquals("gpt-4o-mini", migrated.titleModel?.modelId)
        // 80000 / 100000 = 80%
        assertEquals(80, migrated.compressionTriggerPercent)
        assertEquals("https://search.example.com", migrated.searxngUrl)

        // 迁移后应已写回新版 JSON，再次加载结果一致。
        assertEquals(migrated, ApiSettingsStore.load(preferences))
    }

    @Test
    fun loadReturnsEmptyWhenNothingStored() {
        val settings = ApiSettingsStore.load(InMemorySharedPreferences())
        assertTrue(settings.providers.isEmpty())
        assertNull(settings.chatModel)
        assertEquals(ApiSettings.DefaultCompressionTriggerPercent, settings.compressionTriggerPercent)
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
