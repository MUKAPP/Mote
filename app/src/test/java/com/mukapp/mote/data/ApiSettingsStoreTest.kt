package com.mukapp.mote.data

import android.content.SharedPreferences
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ModelInfo
import com.mukapp.mote.data.model.ModelProvider
import com.mukapp.mote.data.model.ModelRef
import com.mukapp.mote.data.model.ProviderType
import com.mukapp.mote.data.model.resolve
import com.mukapp.mote.data.model.ReasoningEffortOptions
import com.mukapp.mote.data.model.SearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiSettingsStoreTest {

    @Test
    fun saveAndLoadRoundTripsProvidersAndRoleEfforts() {
        val preferences = InMemorySharedPreferences()
        val provider = ModelProvider(
            id = "provider-1",
            name = "OpenAI",
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test",
            type = ProviderType.DeepSeek,
            models = listOf(
                ModelInfo(
                    id = "chat-model",
                    contextLength = 128_000,
                    reasoningEfforts = linkedMapOf("high" to "deep-high", "max" to "ultra")
                ),
                ModelInfo(
                    id = "title-model",
                    displayName = "标题",
                    reasoningEfforts = mapOf("minimal" to "off", "high" to "title-high")
                )
            )
        )
        val settings = ApiSettings(
            providers = listOf(provider),
            chatModel = ModelRef("provider-1", "chat-model", "max"),
            titleModel = ModelRef("provider-1", "title-model", "minimal"),
            compressionModel = ModelRef("provider-1", "chat-model", "high"),
            compressionTriggerPercent = 70,
            searchProvider = SearchProvider.Anysearch,
            searxngUrl = "https://search.example.com",
            tavilyApiKey = "tvly-test",
            anysearchApiKey = "as-test"
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
        assertEquals(ProviderType.Generic, provider.type)
        assertEquals(2, provider.models.size)
        val chat = provider.models.first { it.id == "gpt-4o" }
        assertEquals(100_000, chat.contextLength)
        assertEquals(mapOf("medium" to "medium"), chat.reasoningEfforts)
        val title = provider.models.first { it.id == "gpt-4o-mini" }
        assertEquals(mapOf("high" to "high"), title.reasoningEfforts)
        assertNotNull(migrated.chatModel)
        assertEquals("gpt-4o", migrated.chatModel?.modelId)
        assertEquals("gpt-4o-mini", migrated.titleModel?.modelId)
        assertNull(migrated.chatModel?.reasoningEffort)
        assertEquals(80, migrated.compressionTriggerPercent)
        assertEquals("https://search.example.com", migrated.searxngUrl)
        assertEquals(SearchProvider.Searxng, migrated.searchProvider)
        assertEquals(migrated, ApiSettingsStore.load(preferences))
    }

    @Test
    fun loadsLegacyModelFieldIntoMappedTier() {
        val preferences = InMemorySharedPreferences()
        preferences.edit()
            .putString(
                "settings_json",
                """{"providers":[{"id":"p","type":"generic","models":[{"id":"m","reasoningEffort":"max"}]}],"chatModel":{"providerId":"p","modelId":"m","reasoningEffort":"max"}}"""
            )
            .commit()

        val loaded = ApiSettingsStore.load(preferences)

        assertEquals(mapOf("max" to "max"), loaded.providers.first().models.first().reasoningEfforts)
        assertEquals(ModelRef("p", "m", "max"), loaded.chatModel)
    }

    @Test
    fun normalizesEmptyMappingsAndInvalidRoleEfforts() {
        val preferences = InMemorySharedPreferences()
        preferences.edit()
            .putString(
                "settings_json",
                """{"providers":[{"id":"p","baseUrl":"https://api.example.com/v1","type":"generic","models":[{"id":"m","reasoningEfforts":{}}]}],"chatModel":{"providerId":"p","modelId":"m","reasoningEffort":"unknown"}}"""
            )
            .commit()

        val loaded = ApiSettingsStore.load(preferences)

        assertEquals(
            ReasoningEffortOptions.defaultMappingFor(ProviderType.Generic).keys,
            loaded.providers.first().models.first().reasoningEfforts.keys
        )
        assertNull(loaded.chatModel?.reasoningEffort)
        assertNotNull(loaded.resolve(ModelRef("p", "m", "unknown")))
    }

    @Test
    fun clearsRoleEffortWhenItIsDisabledByModelMapping() {
        val preferences = InMemorySharedPreferences()
        preferences.edit()
            .putString(
                "settings_json",
                """{"providers":[{"id":"p","baseUrl":"https://api.example.com/v1","type":"generic","models":[{"id":"m","reasoningEfforts":{"high":"server-high"}}]}],"chatModel":{"providerId":"p","modelId":"m","reasoningEffort":"low"}}"""
            )
            .commit()

        val loaded = ApiSettingsStore.load(preferences)

        assertNull(loaded.chatModel?.reasoningEffort)
        assertEquals("high", loaded.resolve(loaded.chatModel)?.reasoningEffortKey)
        assertEquals("server-high", loaded.resolve(loaded.chatModel)?.reasoningEffortValue)
    }

    @Test
    fun resolveUsesRoleSelectedMappingValue() {
        val settings = ApiSettings(
            providers = listOf(
                ModelProvider(
                    id = "provider",
                    baseUrl = "https://api.example.com/v1",
                    models = listOf(
                        ModelInfo(id = "model", reasoningEfforts = mapOf("max" to "ultra"))
                    )
                )
            ),
            chatModel = ModelRef("provider", "model", "max")
        )

        val resolved = settings.resolve(settings.chatModel)

        assertEquals("max", resolved?.reasoningEffortKey)
        assertEquals("ultra", resolved?.reasoningEffortValue)
    }

    @Test
    fun loadInfersSearchProviderFromSettingsSavedBeforeProviderSelectionWasAdded() {
        val preferences = InMemorySharedPreferences()
        preferences.edit()
            .putString(
                "settings_json",
                """{"tavilyApiKey":"tvly-legacy","anysearchApiKey":"as-legacy"}"""
            )
            .commit()

        val loaded = ApiSettingsStore.load(preferences)

        assertEquals(SearchProvider.Tavily, loaded.searchProvider)
        assertEquals("tvly-legacy", loaded.tavilyApiKey)
        assertEquals("as-legacy", loaded.anysearchApiKey)
    }

    @Test
    fun loadKeepsConfiguredReasoningMapping() {
        val preferences = InMemorySharedPreferences()
        val settings = ApiSettings(
            providers = listOf(
                ModelProvider(
                    id = "provider-qwen",
                    type = ProviderType.Qwen,
                    models = listOf(
                        ModelInfo(id = "qwen", reasoningEfforts = mapOf("high" to "ignored"))
                    )
                )
            )
        )

        ApiSettingsStore.save(preferences, settings)

        val loaded = ApiSettingsStore.load(preferences)
        assertEquals(mapOf("high" to "ignored"), loaded.providers.first().models.first().reasoningEfforts)
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
