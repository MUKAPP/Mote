package com.mukapp.mote.data

import android.content.Context
import com.mukapp.mote.data.model.ApiSettings

object ApiSettingsStore {
    private const val PrefName = "mote_api_settings"
    private const val KeyBaseUrl = "base_url"
    private const val KeyApiKey = "api_key"
    private const val KeyModel = "model"
    private const val KeyTitleModel = "title_model"
    private const val KeyReasoningEffort = "reasoning_effort"

    fun load(context: Context): ApiSettings {
        val preferences = context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
        return ApiSettings(
            baseUrl = preferences.getString(KeyBaseUrl, "").orEmpty(),
            apiKey = preferences.getString(KeyApiKey, "").orEmpty(),
            model = preferences.getString(KeyModel, "").orEmpty(),
            titleModel = preferences.getString(KeyTitleModel, "").orEmpty(),
            reasoningEffort = preferences.getString(KeyReasoningEffort, "high").orEmpty()
        )
    }

    fun save(context: Context, settings: ApiSettings) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putString(KeyBaseUrl, settings.baseUrl)
            .putString(KeyApiKey, settings.apiKey)
            .putString(KeyModel, settings.model)
            .putString(KeyTitleModel, settings.titleModel)
            .putString(KeyReasoningEffort, settings.reasoningEffort)
            .apply()
    }
}
