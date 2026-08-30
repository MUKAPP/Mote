package com.mukapp.mote.data

import android.content.Context
import android.content.SharedPreferences
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ModelInfo
import com.mukapp.mote.data.model.ModelProvider
import com.mukapp.mote.data.model.ModelRef
import com.mukapp.mote.data.model.ProviderType
import com.mukapp.mote.data.model.ReasoningEffortOptions
import com.mukapp.mote.data.model.SearchProvider
import com.mukapp.mote.data.model.findProvider
import com.mukapp.mote.util.MoteLog
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

object ApiSettingsStore {
    private const val PrefName = "mote_api_settings"

    // 新版：整体 JSON
    private const val KeySettingsJson = "settings_json"

    // 旧版原始键（仅用于迁移）
    private const val LegacyKeyBaseUrl = "base_url"
    private const val LegacyKeyApiKey = "api_key"
    private const val LegacyKeyModel = "model"
    private const val LegacyKeyTitleModel = "title_model"
    private const val LegacyKeyCompressionModel = "compression_model"
    private const val LegacyKeyModelContextLength = "model_context_length"
    private const val LegacyKeyCompressionTriggerLength = "compression_trigger_length"
    private const val LegacyKeySearxngUrl = "searxng_url"
    private const val LegacyKeyTavilyApiKey = "tavily_api_key"
    private const val LegacyKeyReasoningEffort = "reasoning_effort"

    fun load(context: Context): ApiSettings {
        return load(context.getSharedPreferences(PrefName, Context.MODE_PRIVATE))
    }

    internal fun load(preferences: SharedPreferences): ApiSettings {
        val json = preferences.getString(KeySettingsJson, null)
        val settings = if (!json.isNullOrBlank()) {
            runCatching { deserialize(JSONObject(json)) }.getOrElse { error ->
                MoteLog.w("Settings", "解析 API 设置 JSON 失败，回退为空设置。", error)
                ApiSettings()
            }
        } else if (preferences.contains(LegacyKeyBaseUrl) || preferences.contains(LegacyKeyModel)) {
            migrateLegacy(preferences).also { migrated ->
                // 迁移结果回写为新版 JSON，下次直接读取。
                preferences.edit { putString(KeySettingsJson, serialize(migrated).toString()) }
                MoteLog.i("Settings", "已迁移旧版单提供商设置为多提供商结构。")
            }
        } else {
            ApiSettings()
        }
        MoteLog.d("Settings", MoteLog.event("已加载 API 设置", *settings.safeLogFields()))
        return settings
    }

    fun save(context: Context, settings: ApiSettings) {
        save(context.getSharedPreferences(PrefName, Context.MODE_PRIVATE), settings)
    }

    internal fun save(preferences: SharedPreferences, settings: ApiSettings) {
        preferences.edit {
            putString(KeySettingsJson, serialize(settings).toString())
            // 清理旧键，避免重复迁移。
            remove(LegacyKeyBaseUrl)
            remove(LegacyKeyApiKey)
            remove(LegacyKeyModel)
            remove(LegacyKeyTitleModel)
            remove(LegacyKeyCompressionModel)
            remove(LegacyKeyModelContextLength)
            remove(LegacyKeyCompressionTriggerLength)
            remove(LegacyKeySearxngUrl)
            remove(LegacyKeyTavilyApiKey)
            remove(LegacyKeyReasoningEffort)
        }
        MoteLog.i("Settings", MoteLog.event("已保存 API 设置", *settings.safeLogFields()))
    }

    // ==================== 序列化 ====================

    /** 提供商与编辑页之间通过 JSON 字符串传递。 */
    fun providerToJson(provider: ModelProvider): String = serializeProvider(provider).toString()

    fun providerFromJson(json: String): ModelProvider? {
        return runCatching { deserializeProvider(JSONObject(json)) }.getOrNull()
    }

    private fun serialize(settings: ApiSettings): JSONObject {
        return JSONObject().apply {
            put("providers", JSONArray().apply {
                settings.providers.forEach { provider -> put(serializeProvider(provider)) }
            })
            settings.chatModel?.let { put("chatModel", serializeRef(it)) }
            settings.titleModel?.let { put("titleModel", serializeRef(it)) }
            settings.compressionModel?.let { put("compressionModel", serializeRef(it)) }
            put("compressionTriggerPercent", settings.compressionTriggerPercent)
            settings.searchProvider?.let { put("searchProvider", it.storageKey) }
            put("searxngUrl", settings.searxngUrl)
            put("tavilyApiKey", settings.tavilyApiKey)
            put("anysearchApiKey", settings.anysearchApiKey)
        }
    }

    private fun serializeProvider(provider: ModelProvider): JSONObject {
        return JSONObject().apply {
            put("id", provider.id)
            put("name", provider.name)
            put("baseUrl", provider.baseUrl)
            put("apiKey", provider.apiKey)
            put("type", provider.type.storageKey)
            put("models", JSONArray().apply {
                provider.models.forEach { model -> put(serializeModel(model, provider.type)) }
            })
        }
    }

    private fun serializeModel(model: ModelInfo, providerType: ProviderType): JSONObject {
        return JSONObject().apply {
            put("id", model.id)
            put("displayName", model.displayName)
            put("contextLength", model.contextLength)
            put(
                "reasoningEfforts",
                JSONObject().apply {
                    ReasoningEffortOptions.normalizeMapping(providerType, model.reasoningEfforts)
                        .forEach { (key, value) -> put(key, value) }
                }
            )
        }
    }

    private fun serializeRef(ref: ModelRef): JSONObject {
        return JSONObject().apply {
            put("providerId", ref.providerId)
            put("modelId", ref.modelId)
            ref.reasoningEffort?.trim()?.takeIf { it.isNotBlank() }?.let {
                put("reasoningEffort", it)
            }
        }
    }

    private fun deserialize(root: JSONObject): ApiSettings {
        val providers = root.optJSONArray("providers")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(deserializeProvider(item))
                }
            }
        }.orEmpty()
        return ApiSettings(
            providers = providers,
            chatModel = normalizeRef(providers, deserializeRef(root.optJSONObject("chatModel"))),
            titleModel = normalizeRef(providers, deserializeRef(root.optJSONObject("titleModel"))),
            compressionModel = normalizeRef(
                providers,
                deserializeRef(root.optJSONObject("compressionModel"))
            ),
            compressionTriggerPercent = root
                .optInt("compressionTriggerPercent", ApiSettings.DefaultCompressionTriggerPercent)
                .coerceIn(0, 100),
            searchProvider = SearchProvider.fromStorage(root.optString("searchProvider"))
                ?: inferSearchProvider(
                    searxngUrl = root.optString("searxngUrl"),
                    tavilyApiKey = root.optString("tavilyApiKey"),
                    anysearchApiKey = root.optString("anysearchApiKey")
                ),
            searxngUrl = root.optString("searxngUrl"),
            tavilyApiKey = root.optString("tavilyApiKey"),
            anysearchApiKey = root.optString("anysearchApiKey")
        )
    }

    private fun deserializeProvider(json: JSONObject): ModelProvider {
        val providerType = ProviderType.fromStorage(json.optString("type"))
        val models = json.optJSONArray("models")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isBlank()) continue
                    val reasoningEfforts = if (item.has("reasoningEfforts")) {
                        val effortsJson = item.optJSONObject("reasoningEfforts")
                        val efforts = linkedMapOf<String, String>()
                        if (effortsJson != null) {
                            val keys = effortsJson.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val value = effortsJson.optString(key)
                                efforts[key] = value
                            }
                        }
                        ReasoningEffortOptions.normalizeMapping(providerType, efforts)
                    } else {
                        ReasoningEffortOptions.mappingFromLegacyKey(
                            providerType,
                            item.optString("reasoningEffort")
                        )
                    }
                    add(
                        ModelInfo(
                            id = id,
                            displayName = item.optString("displayName"),
                            contextLength = item.optInt("contextLength", 0).coerceAtLeast(0),
                            reasoningEfforts = reasoningEfforts
                        )
                    )
                }
            }
        }.orEmpty()
        return ModelProvider(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = json.optString("name"),
            baseUrl = json.optString("baseUrl"),
            apiKey = json.optString("apiKey"),
            type = providerType,
            models = models
        )
    }

    private fun deserializeRef(json: JSONObject?): ModelRef? {
        json ?: return null
        val providerId = json.optString("providerId")
        val modelId = json.optString("modelId")
        if (providerId.isBlank() || modelId.isBlank()) return null
        return ModelRef(
            providerId = providerId,
            modelId = modelId,
            reasoningEffort = json.optString("reasoningEffort")
                .trim()
                .takeIf { it.isNotBlank() }
        )
    }

    private fun normalizeRef(providers: List<ModelProvider>, ref: ModelRef?): ModelRef? {
        ref ?: return null
        val provider = providers.firstOrNull { it.id == ref.providerId } ?: return null
        val model = provider.models.firstOrNull { it.id == ref.modelId } ?: return null
        val reasoningEfforts = ReasoningEffortOptions.normalizeMapping(
            provider.type,
            model.reasoningEfforts
        )
        val rawKey = ref.reasoningEffort?.trim()?.lowercase()
        val normalizedKey = rawKey?.let {
            ReasoningEffortOptions.normalizeKey(provider.type, it, reasoningEfforts)
        }
        return ref.copy(reasoningEffort = rawKey?.takeIf {
            normalizedKey == it && it in reasoningEfforts
        })
    }

    // ==================== 旧版迁移 ====================

    private fun migrateLegacy(preferences: SharedPreferences): ApiSettings {
        val baseUrl = preferences.getString(LegacyKeyBaseUrl, "").orEmpty().trim()
        val apiKey = preferences.getString(LegacyKeyApiKey, "").orEmpty().trim()
        val chatModelId = preferences.getString(LegacyKeyModel, "").orEmpty().trim()
        val titleModelId = preferences.getString(LegacyKeyTitleModel, "").orEmpty().trim()
        val compressionModelId = preferences.getString(LegacyKeyCompressionModel, "").orEmpty().trim()
        val contextLength = preferences.getInt(LegacyKeyModelContextLength, 0).coerceAtLeast(0)
        val triggerLength = preferences.getInt(LegacyKeyCompressionTriggerLength, 0).coerceAtLeast(0)
        val reasoningEffort = preferences.getString(LegacyKeyReasoningEffort, "high").orEmpty().ifBlank { "high" }
        val searxngUrl = preferences.getString(LegacyKeySearxngUrl, "").orEmpty()
        val tavilyApiKey = preferences.getString(LegacyKeyTavilyApiKey, "").orEmpty()

        if (baseUrl.isBlank() && chatModelId.isBlank()) {
            return ApiSettings(
                searchProvider = inferSearchProvider(searxngUrl, tavilyApiKey, ""),
                searxngUrl = searxngUrl,
                tavilyApiKey = tavilyApiKey
            )
        }

        val providerId = UUID.randomUUID().toString()
        val chatReasoningEfforts = ReasoningEffortOptions.mappingFromLegacyKey(
            ProviderType.Generic,
            reasoningEffort
        )
        val defaultReasoningEfforts = ReasoningEffortOptions.mappingFromLegacyKey(
            ProviderType.Generic,
            ReasoningEffortOptions.defaultKeyFor(ProviderType.Generic)
        )
        val models = linkedMapOf<String, ModelInfo>()
        if (chatModelId.isNotBlank()) {
            models[chatModelId] = ModelInfo(
                id = chatModelId,
                contextLength = contextLength,
                reasoningEfforts = chatReasoningEfforts
            )
        }
        if (titleModelId.isNotBlank() && !models.containsKey(titleModelId)) {
            models[titleModelId] = ModelInfo(
                id = titleModelId,
                reasoningEfforts = defaultReasoningEfforts
            )
        }
        if (compressionModelId.isNotBlank() && !models.containsKey(compressionModelId)) {
            models[compressionModelId] = ModelInfo(
                id = compressionModelId,
                reasoningEfforts = defaultReasoningEfforts
            )
        }

        val provider = ModelProvider(
            id = providerId,
            name = legacyProviderName(baseUrl),
            baseUrl = baseUrl,
            apiKey = apiKey,
            models = models.values.toList()
        )

        val triggerPercent = if (contextLength > 0 && triggerLength > 0) {
            (triggerLength.toDouble() / contextLength * 100).roundToInt().coerceIn(1, 100)
        } else {
            ApiSettings.DefaultCompressionTriggerPercent
        }

        return ApiSettings(
            providers = listOf(provider),
            chatModel = chatModelId.takeIf { it.isNotBlank() }?.let { ModelRef(providerId, it) },
            titleModel = titleModelId.takeIf { it.isNotBlank() }?.let { ModelRef(providerId, it) },
            compressionModel = compressionModelId.takeIf { it.isNotBlank() }
                ?.let { ModelRef(providerId, it) },
            compressionTriggerPercent = triggerPercent,
            searchProvider = inferSearchProvider(searxngUrl, tavilyApiKey, ""),
            searxngUrl = searxngUrl,
            tavilyApiKey = tavilyApiKey
        )
    }

    private fun inferSearchProvider(
        searxngUrl: String,
        tavilyApiKey: String,
        anysearchApiKey: String
    ): SearchProvider? {
        return when {
            searxngUrl.isNotBlank() -> SearchProvider.Searxng
            tavilyApiKey.isNotBlank() -> SearchProvider.Tavily
            anysearchApiKey.isNotBlank() -> SearchProvider.Anysearch
            else -> null
        }
    }

    private fun legacyProviderName(baseUrl: String): String {
        val host = runCatching { java.net.URI(baseUrl).host }.getOrNull()
        return host?.takeIf { it.isNotBlank() } ?: "默认提供商"
    }

    private fun ApiSettings.safeLogFields(): Array<Pair<String, Any?>> {
        return arrayOf(
            "providers" to providers.size,
            "models" to providers.sumOf { it.models.size },
            "chatModelConfigured" to (resolvedRefConfigured(chatModel)),
            "titleModelConfigured" to (titleModel != null),
            "compressionModelConfigured" to (compressionModel != null),
            "compressionTriggerPercent" to compressionTriggerPercent,
            "searchProvider" to (searchProvider?.storageKey ?: "未选择"),
            "searxngConfigured" to searxngUrl.isNotBlank(),
            "tavilyConfigured" to tavilyApiKey.isNotBlank(),
            "anysearchConfigured" to anysearchApiKey.isNotBlank()
        )
    }

    private fun ApiSettings.resolvedRefConfigured(ref: ModelRef?): Boolean {
        return ref != null && findProvider(ref.providerId)?.models?.any { it.id == ref.modelId } == true
    }
}
