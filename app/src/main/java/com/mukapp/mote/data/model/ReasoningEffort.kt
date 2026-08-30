package com.mukapp.mote.data.model

import com.mukapp.mote.R

/**
 * 提供商类型。不同家控制“推理”的请求字段完全不同，类型决定了：
 * 1) 设置页/底部框可选的推理强度档位；
 * 2) 每个档位写入请求体的实际字段（见 [ReasoningEffortOptions.encode]）。
 *
 * `storageKey` 持久化到 JSON；新增类型务必保持已有 key 不变。
 */
enum class ProviderType(val storageKey: String) {
    Generic("generic"),
    DeepSeek("deepseek"),
    Gemini("gemini"),
    Qwen("qwen"),
    Claude("claude");

    companion object {
        /** 反序列化：未知或缺省一律回退 [Generic]，保证旧数据兼容。 */
        fun fromStorage(key: String?): ProviderType =
            entries.firstOrNull { it.storageKey == key } ?: Generic
    }
}

/** 单个推理强度档位：稳定 `key`（存入 [ModelRef.reasoningEffort]）+ 本地化标签资源。 */
data class ReasoningOption(val key: String, val labelRes: Int)

/**
 * 某档位需要写入请求体的字段。Mote 用 org.json 手搓请求体、不走 OpenAI SDK。
 * SDK 文档中通过 `extra_body` 透传的字段，在 REST 请求体中保留为顶层 `extra_body` 对象。
 *
 * @param reasoningEffort 顶层 `reasoning_effort`，为 null 时不写。
 * @param topLevel 其余顶层键值（如 `thinking`/`enable_thinking`/`thinking_budget`/`max_tokens`）。
 *   值可为 String/Boolean/Int 或嵌套 [Map]，由网络层递归转 JSON。
 */
data class ReasoningRequestFields(
    val reasoningEffort: String? = null,
    val topLevel: Map<String, Any?> = emptyMap()
)

/** 推理强度档位、模型映射与请求编码的单一事实来源。 */
object ReasoningEffortOptions {

    val standardKeys: List<String> = listOf("minimal", "low", "medium", "high", "xhigh", "max")

    private val standardOptions = listOf(
        ReasoningOption("minimal", R.string.settings_reasoning_minimal),
        ReasoningOption("low", R.string.settings_reasoning_low),
        ReasoningOption("medium", R.string.settings_reasoning_medium),
        ReasoningOption("high", R.string.settings_reasoning_high),
        ReasoningOption("xhigh", R.string.settings_reasoning_xhigh),
        ReasoningOption("max", R.string.settings_reasoning_max)
    )

    /** Claude Messages API 必须携带 max_tokens；这里使用保守默认值，避免超过常见兼容网关上限。 */
    private const val ClaudeMaxTokens = 8192

    /** 按现有提供商能力初始化可用档位；用户可在模型编辑器中扩展或改写映射。 */
    fun defaultMappingFor(type: ProviderType): Map<String, String> = when (type) {
        ProviderType.Generic -> linkedMapOf(
            "low" to "low",
            "medium" to "medium",
            "high" to "high",
            "xhigh" to "xhigh"
        )
        ProviderType.DeepSeek -> linkedMapOf(
            "minimal" to "off",
            "high" to "high",
            "max" to "max"
        )
        ProviderType.Gemini -> linkedMapOf(
            "minimal" to "none",
            "low" to "low",
            "high" to "high"
        )
        ProviderType.Qwen -> linkedMapOf(
            "minimal" to "off",
            "high" to "on"
        )
        ProviderType.Claude -> linkedMapOf(
            "minimal" to "off",
            "high" to "high"
        )
    }

    /** 缺失映射项时用于填充编辑框的默认请求值。 */
    fun defaultValueFor(type: ProviderType, key: String): String {
        val normalized = key.trim().lowercase().takeIf { it in standardKeys } ?: defaultKeyFor(type)
        return when (type) {
            ProviderType.DeepSeek -> if (normalized == "minimal") "off" else normalized
            ProviderType.Gemini -> if (normalized == "minimal") "none" else normalized
            ProviderType.Qwen -> if (normalized == "minimal") "off" else "on"
            ProviderType.Claude -> if (normalized == "minimal") "off" else normalized
            ProviderType.Generic -> normalized
        }
    }

    fun optionsFor(type: ProviderType): List<ReasoningOption> = standardOptions

    /** 旧模型没有映射时的稳定回退档位。 */
    fun defaultKeyFor(type: ProviderType): String = "high"

    /** 返回模型当前启用的档位，始终按六档标准顺序排列。 */
    fun enabledKeys(type: ProviderType, reasoningEfforts: Map<String, String>?): List<String> {
        return normalizeMapping(type, reasoningEfforts).keys.toList()
    }

    /** 清理未知 key、空请求值，并在没有有效档位时回退默认映射。 */
    fun normalizeMapping(
        type: ProviderType,
        reasoningEfforts: Map<String, String>?
    ): Map<String, String> {
        val valuesByKey = mutableMapOf<String, String>()
        reasoningEfforts.orEmpty().forEach { (rawKey, rawValue) ->
            val key = rawKey.trim().lowercase().takeIf { it in standardKeys } ?: return@forEach
            val value = rawValue.trim()
            if (value.isNotBlank()) {
                valuesByKey.putIfAbsent(key, value)
            }
        }
        if (valuesByKey.isEmpty()) {
            return defaultMappingFor(type)
        }
        return linkedMapOf<String, String>().apply {
            standardKeys.forEach { key ->
                valuesByKey[key]?.let { put(key, it) }
            }
        }
    }

    /**
     * 把标准 key 或旧 provider-specific key 归一化为六档 key。
     * 映射参数存在时，禁用/未知档位优先回退默认档，否则回退第一个启用档位。
     */
    fun normalizeKey(
        type: ProviderType,
        key: String?,
        reasoningEfforts: Map<String, String>? = null
    ): String {
        val raw = key?.trim()?.lowercase()
        val candidate = when (raw) {
            "off", "none" -> "minimal"
            "on" -> "high"
            else -> raw?.takeIf { it in standardKeys }
        } ?: defaultKeyFor(type)
        val enabled = reasoningEfforts?.let { enabledKeys(type, it) }
        if (enabled == null || candidate in enabled) {
            return candidate
        }
        return if (defaultKeyFor(type) in enabled) {
            defaultKeyFor(type)
        } else {
            enabled.firstOrNull() ?: defaultKeyFor(type)
        }
    }

    /** 用旧单档位生成兼容的新模型映射，保留迁移前的有效选择。 */
    fun mappingFromLegacyKey(type: ProviderType, key: String?): Map<String, String> {
        val normalized = legacyKeyFor(type, key)
        return linkedMapOf(normalized to defaultValueFor(type, normalized))
    }

    private fun legacyKeyFor(type: ProviderType, key: String?): String {
        val raw = key?.trim()?.lowercase()
        return when (type) {
            ProviderType.Generic -> when (raw) {
                "off", "none" -> "minimal"
                "on" -> "high"
                else -> raw?.takeIf { it in standardKeys } ?: defaultKeyFor(type)
            }
            ProviderType.DeepSeek -> when (raw) {
                "off", "none" -> "minimal"
                "low", "medium" -> "high"
                "xhigh" -> "max"
                "high", "max" -> raw
                else -> defaultKeyFor(type)
            }
            ProviderType.Gemini -> when (raw) {
                "off", "none" -> "minimal"
                "low", "high" -> raw
                else -> defaultKeyFor(type)
            }
            ProviderType.Qwen, ProviderType.Claude -> when (raw) {
                "off", "none" -> "minimal"
                "on", "high" -> "high"
                "minimal" -> "minimal"
                else -> defaultKeyFor(type)
            }
        }
    }

    /** 返回（归一化后的）档位标签资源 id。 */
    fun labelRes(type: ProviderType, key: String?): Int {
        val normalized = normalizeKey(type, key)
        return standardOptions.first { it.key == normalized }.labelRes
    }

    /** 把档位与模型映射值翻译成请求体字段；这是唯一的请求编码入口。 */
    fun encode(type: ProviderType, key: String?, value: String): ReasoningRequestFields {
        val normalized = normalizeKey(type, key)
        val requestValue = value.trim().ifBlank { normalized }
        return when (type) {
            ProviderType.Generic -> ReasoningRequestFields(reasoningEffort = requestValue)

            ProviderType.Gemini -> ReasoningRequestFields(
                topLevel = mapOf(
                    "extra_body" to mapOf(
                        "google" to mapOf(
                            "thinking_config" to if (normalized == "minimal") {
                                mapOf("thinking_budget" to 0)
                            } else {
                                mapOf(
                                    "thinking_level" to requestValue,
                                    "include_thoughts" to true
                                )
                            }
                        )
                    )
                )
            )

            ProviderType.DeepSeek -> if (normalized == "minimal") {
                ReasoningRequestFields(
                    topLevel = mapOf("thinking" to mapOf("type" to "disabled"))
                )
            } else {
                ReasoningRequestFields(
                    reasoningEffort = requestValue,
                    topLevel = mapOf("thinking" to mapOf("type" to "enabled"))
                )
            }

            ProviderType.Qwen -> ReasoningRequestFields(
                topLevel = mapOf("enable_thinking" to (normalized != "minimal"))
            )

            ProviderType.Claude -> if (normalized == "minimal") {
                ReasoningRequestFields(
                    topLevel = mapOf("max_tokens" to ClaudeMaxTokens)
                )
            } else {
                ReasoningRequestFields(
                    topLevel = mapOf(
                        "thinking" to mapOf("type" to "adaptive"),
                        "output_config" to mapOf("effort" to requestValue),
                        "max_tokens" to ClaudeMaxTokens
                    )
                )
            }
        }
    }
}
