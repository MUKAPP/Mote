package com.mukapp.mote.data.model

import com.mukapp.mote.R

/**
 * 提供商类型。不同家控制"思考/推理"的请求字段完全不同，类型决定了：
 * 1) 设置页/底部框可选的思考强度档位；
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

/** 单个思考强度档位：稳定 `key`（存入 [ModelInfo.reasoningEffort]）+ 本地化标签资源。 */
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

/** 思考强度档位表与请求编码的单一事实来源，供 设置页 / 底部框 / 副标题 / 网络层 共用。 */
object ReasoningEffortOptions {

    private val generic = listOf(
        ReasoningOption("low", R.string.settings_reasoning_low),
        ReasoningOption("medium", R.string.settings_reasoning_medium),
        ReasoningOption("high", R.string.settings_reasoning_high),
        ReasoningOption("xhigh", R.string.settings_reasoning_xhigh)
    )

    private val deepSeek = listOf(
        ReasoningOption("off", R.string.reasoning_off),
        ReasoningOption("high", R.string.settings_reasoning_high),
        ReasoningOption("max", R.string.reasoning_max)
    )

    private val gemini = listOf(
        ReasoningOption("none", R.string.reasoning_off),
        ReasoningOption("low", R.string.settings_reasoning_low),
        ReasoningOption("high", R.string.settings_reasoning_high)
    )

    private val qwen = listOf(
        ReasoningOption("off", R.string.reasoning_off),
        ReasoningOption("on", R.string.reasoning_on)
    )

    private val claude = listOf(
        ReasoningOption("off", R.string.reasoning_off),
        ReasoningOption("on", R.string.reasoning_on)
    )

    /** Claude Messages API 必须携带 max_tokens；这里使用保守默认值，避免超过常见兼容网关上限。 */
    private const val ClaudeMaxTokens = 8192

    fun optionsFor(type: ProviderType): List<ReasoningOption> = when (type) {
        ProviderType.Generic -> generic
        ProviderType.DeepSeek -> deepSeek
        ProviderType.Gemini -> gemini
        ProviderType.Qwen -> qwen
        ProviderType.Claude -> claude
    }

    fun defaultKeyFor(type: ProviderType): String = when (type) {
        ProviderType.Generic -> "high"
        ProviderType.DeepSeek -> "high"
        ProviderType.Gemini -> "high"
        ProviderType.Qwen -> "on"
        ProviderType.Claude -> "on"
    }

    /** 档位 key 在该类型档位表内则原样返回，否则回退默认档。 */
    fun normalizeKey(type: ProviderType, key: String?): String {
        val options = optionsFor(type)
        options.firstOrNull { it.key == key }?.key?.let { return it }
        return when (type) {
            ProviderType.DeepSeek -> when (key) {
                "low", "medium" -> "high"
                "xhigh" -> "max"
                else -> defaultKeyFor(type)
            }
            else -> defaultKeyFor(type)
        }
    }

    /** 返回（归一化后的）档位标签资源 id。 */
    fun labelRes(type: ProviderType, key: String?): Int {
        val normalized = normalizeKey(type, key)
        val options = optionsFor(type)
        return (options.firstOrNull { it.key == normalized } ?: options.first()).labelRes
    }

    /** 把档位翻译成请求体顶层字段。纯函数，无 Android 依赖，便于单测。 */
    fun encode(type: ProviderType, key: String?): ReasoningRequestFields {
        val normalized = normalizeKey(type, key)
        return when (type) {
            ProviderType.Generic -> ReasoningRequestFields(reasoningEffort = normalized)

            ProviderType.Gemini -> ReasoningRequestFields(
                topLevel = mapOf(
                    "extra_body" to mapOf(
                        "google" to mapOf(
                            "thinking_config" to if (normalized == "none") {
                                mapOf("thinking_budget" to 0)
                            } else {
                                mapOf(
                                    "thinking_level" to normalized,
                                    "include_thoughts" to true
                                )
                            }
                        )
                    )
                )
            )

            ProviderType.DeepSeek -> when (normalized) {
                "off" -> ReasoningRequestFields(
                    topLevel = mapOf("thinking" to mapOf("type" to "disabled"))
                )
                else -> ReasoningRequestFields(
                    reasoningEffort = normalized,
                    topLevel = mapOf("thinking" to mapOf("type" to "enabled"))
                )
            }

            ProviderType.Qwen -> ReasoningRequestFields(
                topLevel = mapOf("enable_thinking" to (normalized == "on"))
            )

            ProviderType.Claude -> if (normalized == "on") {
                ReasoningRequestFields(
                    topLevel = mapOf(
                        "thinking" to mapOf("type" to "adaptive"),
                        "output_config" to mapOf("effort" to "high"),
                        "max_tokens" to ClaudeMaxTokens
                    )
                )
            } else {
                ReasoningRequestFields(
                    topLevel = mapOf("max_tokens" to ClaudeMaxTokens)
                )
            }
        }
    }
}
