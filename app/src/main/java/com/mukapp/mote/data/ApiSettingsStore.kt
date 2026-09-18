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
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object ApiSettingsStore {
    private const val PrefName = "mote_api_settings"

    // 内存缓存 + 单线程写队列：load 命中缓存时不重复做 JSON 解析与 Keystore 解密，
    // save 的序列化/加密不占用调用线程（通常是主线程）。缓存只作用于 Context 级 API，
    // internal 的 preferences 重载保持同步直连以便 JVM 测试。
    @Volatile
    private var cachedSettings: ApiSettings? = null
    private val persistExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ApiSettingsWriter")
    }

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
        cachedSettings?.let { return it }
        val preferences = context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
        val storedJson = preferences.getString(KeySettingsJson, null)
        val settings = load(preferences, KeystoreSecretCodec)
        // 存量明文密钥升级：解析成功且发现明文密钥时立即以加密形式回写。
        if (!storedJson.isNullOrBlank() && containsPlaintextSecret(storedJson)) {
            persistExecutor.execute {
                runCatching { save(preferences, settings, KeystoreSecretCodec) }
                    .onSuccess { MoteLog.i("Settings", "已将明文密钥升级为加密存储。") }
                    .onFailure { error -> MoteLog.w("Settings", "明文密钥升级保存失败。", error) }
            }
        }
        cachedSettings = settings
        return settings
    }

    internal fun load(preferences: SharedPreferences, codec: SecretCodec = PlainSecretCodec): ApiSettings {
        val json = preferences.getString(KeySettingsJson, null)
        val settings = if (!json.isNullOrBlank()) {
            runCatching { deserialize(JSONObject(json), codec) }.getOrElse { error ->
                MoteLog.w("Settings", "解析 API 设置 JSON 失败，回退为空设置。", error)
                ApiSettings()
            }
        } else if (preferences.contains(LegacyKeyBaseUrl) || preferences.contains(LegacyKeyModel)) {
            migrateLegacy(preferences).also { migrated ->
                // 迁移结果回写为新版 JSON，下次直接读取；同时清除旧版明文键。
                preferences.edit {
                    putString(KeySettingsJson, serialize(migrated, codec).toString())
                    removeLegacyKeys()
                }
                MoteLog.i("Settings", "已迁移旧版单提供商设置为多提供商结构。")
            }
        } else {
            ApiSettings()
        }
        MoteLog.d("Settings", MoteLog.event("已加载 API 设置", *settings.safeLogFields()))
        return settings
    }

    fun save(context: Context, settings: ApiSettings) {
        // 先更新内存缓存保证同进程读到最新值；序列化 + Keystore 加密移到写线程，
        // 磁盘落盘本就由 SharedPreferences.apply() 异步完成，持久化语义不变。
        cachedSettings = settings
        val preferences = context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
        persistExecutor.execute {
            runCatching { save(preferences, settings, KeystoreSecretCodec) }
                .onFailure { error -> MoteLog.w("Settings", "后台保存 API 设置失败。", error) }
        }
    }

    internal fun save(
        preferences: SharedPreferences,
        settings: ApiSettings,
        codec: SecretCodec = PlainSecretCodec
    ) {
        preferences.edit {
            putString(KeySettingsJson, serialize(settings, codec).toString())
            removeLegacyKeys()
        }
        MoteLog.i("Settings", MoteLog.event("已保存 API 设置", *settings.safeLogFields()))
    }

    private fun SharedPreferences.Editor.removeLegacyKeys() {
        // 清理旧键，避免重复迁移与明文密钥残留。
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

    /** 存量 JSON 中是否存在未加密的非空密钥字段。解析失败按无处理，与加载回退行为一致。 */
    private fun containsPlaintextSecret(storedJson: String): Boolean {
        return runCatching {
            val root = JSONObject(storedJson)
            val secrets = buildList {
                add(root.optString("tavilyApiKey"))
                add(root.optString("anysearchApiKey"))
                root.optJSONArray("providers")?.let { array ->
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let { add(it.optString("apiKey")) }
                    }
                }
            }
            secrets.any { it.isNotBlank() && !KeystoreSecretCodec.isEncoded(it) }
        }.getOrDefault(false)
    }

    // ==================== 序列化 ====================

    private fun serialize(settings: ApiSettings, codec: SecretCodec): JSONObject {
        return JSONObject().apply {
            put("providers", JSONArray().apply {
                settings.providers.forEach { provider -> put(serializeProvider(provider, codec)) }
            })
            settings.chatModel?.let { put("chatModel", serializeRef(it)) }
            settings.titleModel?.let { put("titleModel", serializeRef(it)) }
            settings.compressionModel?.let { put("compressionModel", serializeRef(it)) }
            put("compressionTriggerPercent", settings.compressionTriggerPercent)
            settings.searchProvider?.let { put("searchProvider", it.storageKey) }
            put("searxngUrl", settings.searxngUrl)
            put("tavilyApiKey", codec.encode(settings.tavilyApiKey))
            put("anysearchApiKey", codec.encode(settings.anysearchApiKey))
        }
    }

    private fun serializeProvider(provider: ModelProvider, codec: SecretCodec): JSONObject {
        return JSONObject().apply {
            put("id", provider.id)
            put("name", provider.name)
            put("baseUrl", provider.baseUrl)
            put("apiKey", codec.encode(provider.apiKey))
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

    private fun deserialize(root: JSONObject, codec: SecretCodec): ApiSettings {
        val providers = root.optJSONArray("providers")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(deserializeProvider(item, codec))
                }
            }
        }.orEmpty()
        val tavilyApiKey = codec.decode(root.optString("tavilyApiKey"))
        val anysearchApiKey = codec.decode(root.optString("anysearchApiKey"))
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
                    tavilyApiKey = tavilyApiKey,
                    anysearchApiKey = anysearchApiKey
                ),
            searxngUrl = root.optString("searxngUrl"),
            tavilyApiKey = tavilyApiKey,
            anysearchApiKey = anysearchApiKey
        )
    }

    private fun deserializeProvider(json: JSONObject, codec: SecretCodec): ModelProvider {
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
            apiKey = codec.decode(json.optString("apiKey")),
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
