package com.mukapp.mote.data

import android.content.Context
import android.content.SharedPreferences
import com.mukapp.mote.data.model.ApiSettings

object ApiSettingsStore {
    private const val PrefName = "mote_api_settings"
    private const val KeyBaseUrl = "base_url"
    private const val KeyApiKey = "api_key"
    private const val KeyModel = "model"
    private const val KeyTitleModel = "title_model"
    private const val KeyCompressionModel = "compression_model"
    private const val KeyModelContextLength = "model_context_length"
    private const val KeyCompressionTriggerLength = "compression_trigger_length"
    private const val KeySearxngUrl = "searxng_url"
    private const val KeyReasoningEffort = "reasoning_effort"

    fun load(context: Context): ApiSettings {
        val preferences = context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
        return load(preferences)
    }

    internal fun load(preferences: SharedPreferences): ApiSettings {
        return ApiSettings(
            baseUrl = preferences.getString(KeyBaseUrl, "").orEmpty(),
            apiKey = preferences.getString(KeyApiKey, "").orEmpty(),
            model = preferences.getString(KeyModel, "").orEmpty(),
            titleModel = preferences.getString(KeyTitleModel, "").orEmpty(),
            compressionModel = preferences.getString(KeyCompressionModel, "").orEmpty(),
            modelContextLength = preferences.getInt(KeyModelContextLength, 0).coerceAtLeast(0),
            compressionTriggerLength = preferences.getInt(KeyCompressionTriggerLength, 0).coerceAtLeast(0),
            searxngUrl = preferences.getString(KeySearxngUrl, "").orEmpty(),
            reasoningEffort = preferences.getString(KeyReasoningEffort, "high").orEmpty()
        )
    }

    fun save(context: Context, settings: ApiSettings) {
        save(context.getSharedPreferences(PrefName, Context.MODE_PRIVATE), settings)
    }

    internal fun save(preferences: SharedPreferences, settings: ApiSettings) {
        preferences.edit()
            .putString(KeyBaseUrl, settings.baseUrl)
            .putString(KeyApiKey, settings.apiKey)
            .putString(KeyModel, settings.model)
            .putString(KeyTitleModel, settings.titleModel)
            .putString(KeyCompressionModel, settings.compressionModel)
            .putInt(KeyModelContextLength, settings.modelContextLength.coerceAtLeast(0))
            .putInt(KeyCompressionTriggerLength, settings.compressionTriggerLength.coerceAtLeast(0))
            .putString(KeySearxngUrl, settings.searxngUrl)
            .putString(KeyReasoningEffort, settings.reasoningEffort)
            .apply()
    }
}
