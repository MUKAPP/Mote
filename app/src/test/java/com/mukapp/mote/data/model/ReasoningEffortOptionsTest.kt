package com.mukapp.mote.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEffortOptionsTest {

    @Test
    fun exposesSixStandardTiers() {
        assertEquals(
            listOf("minimal", "low", "medium", "high", "xhigh", "max"),
            ReasoningEffortOptions.optionsFor(ProviderType.Generic).map { it.key }
        )
        assertEquals("high", ReasoningEffortOptions.defaultKeyFor(ProviderType.Generic))
    }

    @Test
    fun genericUsesModelMappedRequestValue() {
        val fields = ReasoningEffortOptions.encode(ProviderType.Generic, "max", " ultra ")
        assertEquals("ultra", fields.reasoningEffort)
        assertTrue(fields.topLevel.isEmpty())
    }

    @Test
    fun mappingNormalizesUnknownAndEmptyValues() {
        assertEquals(
            mapOf("high" to "custom-high"),
            ReasoningEffortOptions.normalizeMapping(
                ProviderType.Generic,
                mapOf("high" to " custom-high ", "unknown" to "ignored", "low" to " ")
            )
        )
        assertEquals(
            ReasoningEffortOptions.defaultMappingFor(ProviderType.Generic),
            ReasoningEffortOptions.normalizeMapping(ProviderType.Generic, emptyMap())
        )
        assertEquals("minimal", ReasoningEffortOptions.normalizeKey(ProviderType.Qwen, "off"))
        assertEquals("high", ReasoningEffortOptions.normalizeKey(ProviderType.Qwen, "on"))
        assertEquals("minimal", ReasoningEffortOptions.normalizeKey(ProviderType.Gemini, "none"))
        assertEquals(mapOf("minimal" to "off"), ReasoningEffortOptions.mappingFromLegacyKey(ProviderType.DeepSeek, "off"))
        assertEquals(mapOf("high" to "high"), ReasoningEffortOptions.mappingFromLegacyKey(ProviderType.DeepSeek, "medium"))
        assertEquals(mapOf("high" to "on"), ReasoningEffortOptions.mappingFromLegacyKey(ProviderType.Qwen, "on"))
    }

    @Test
    fun disabledTierFallsBackToDefaultEnabledTier() {
        val mapping = linkedMapOf("high" to "server-high", "max" to "server-max")
        assertEquals("high", ReasoningEffortOptions.normalizeKey(ProviderType.Generic, "low", mapping))
        assertEquals(listOf("high", "max"), ReasoningEffortOptions.enabledKeys(ProviderType.Generic, mapping))
    }

    @Test
    fun deepSeekUsesMappedEffortAndThinkingToggle() {
        val minimal = ReasoningEffortOptions.encode(ProviderType.DeepSeek, "minimal", "disabled")
        assertNull(minimal.reasoningEffort)
        assertEquals(mapOf("type" to "disabled"), minimal.topLevel["thinking"])

        val high = ReasoningEffortOptions.encode(ProviderType.DeepSeek, "high", "deep-high")
        assertEquals("deep-high", high.reasoningEffort)
        assertEquals(mapOf("type" to "enabled"), high.topLevel["thinking"])
    }

    @Test
    fun geminiUsesMappedThinkingLevel() {
        val minimal = ReasoningEffortOptions.encode(ProviderType.Gemini, "minimal", "none")
            .topLevel["extra_body"] as Map<*, *>
        val minimalGoogle = minimal["google"] as Map<*, *>
        assertEquals(mapOf("thinking_budget" to 0), minimalGoogle["thinking_config"])

        val high = ReasoningEffortOptions.encode(ProviderType.Gemini, "high", "gemini-high")
            .topLevel["extra_body"] as Map<*, *>
        val highGoogle = high["google"] as Map<*, *>
        assertEquals(
            mapOf("thinking_level" to "gemini-high", "include_thoughts" to true),
            highGoogle["thinking_config"]
        )
    }

    @Test
    fun qwenUsesOnlyEnableThinkingFlag() {
        val minimal = ReasoningEffortOptions.encode(ProviderType.Qwen, "minimal", "ignored")
        assertNull(minimal.reasoningEffort)
        assertEquals(false, minimal.topLevel["enable_thinking"])

        val max = ReasoningEffortOptions.encode(ProviderType.Qwen, "max", "ignored")
        assertEquals(true, max.topLevel["enable_thinking"])
    }

    @Test
    fun claudeUsesMappedOutputEffort() {
        val minimal = ReasoningEffortOptions.encode(ProviderType.Claude, "minimal", "off")
        assertNull(minimal.topLevel["thinking"])
        assertEquals(8192, minimal.topLevel["max_tokens"])

        val high = ReasoningEffortOptions.encode(ProviderType.Claude, "high", "claude-high")
        assertEquals(mapOf("type" to "adaptive"), high.topLevel["thinking"])
        assertEquals(mapOf("effort" to "claude-high"), high.topLevel["output_config"])
        assertEquals(8192, high.topLevel["max_tokens"])
    }
}
