package com.mukapp.mote.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningEffortOptionsTest {

    @Test
    fun genericPassesReasoningEffortThrough() {
        val low = ReasoningEffortOptions.encode(ProviderType.Generic, "low")
        assertEquals("low", low.reasoningEffort)
        assertTrue(low.topLevel.isEmpty())

        val xhigh = ReasoningEffortOptions.encode(ProviderType.Generic, "xhigh")
        assertEquals("xhigh", xhigh.reasoningEffort)
    }

    @Test
    fun unknownKeyFallsBackToTypeDefault() {
        assertEquals("high", ReasoningEffortOptions.normalizeKey(ProviderType.Generic, "nope"))
        assertEquals("high", ReasoningEffortOptions.normalizeKey(ProviderType.DeepSeek, "low"))
        assertEquals("high", ReasoningEffortOptions.normalizeKey(ProviderType.DeepSeek, "medium"))
        assertEquals("max", ReasoningEffortOptions.normalizeKey(ProviderType.DeepSeek, "xhigh"))
        assertEquals("high", ReasoningEffortOptions.normalizeKey(ProviderType.Gemini, "medium"))
        assertEquals("on", ReasoningEffortOptions.normalizeKey(ProviderType.Qwen, null))
        // 归一化后通用类型默认 high。
        assertEquals("high", ReasoningEffortOptions.encode(ProviderType.Generic, "zzz").reasoningEffort)
    }

    @Test
    fun deepSeekTogglesThinkingAndUsesHighMax() {
        val off = ReasoningEffortOptions.encode(ProviderType.DeepSeek, "off")
        assertNull(off.reasoningEffort)
        assertEquals(mapOf("type" to "disabled"), off.topLevel["thinking"])

        val high = ReasoningEffortOptions.encode(ProviderType.DeepSeek, "high")
        assertEquals("high", high.reasoningEffort)
        assertEquals(mapOf("type" to "enabled"), high.topLevel["thinking"])

        val max = ReasoningEffortOptions.encode(ProviderType.DeepSeek, "max")
        assertEquals("max", max.reasoningEffort)
        assertEquals(mapOf("type" to "enabled"), max.topLevel["thinking"])

        val compatibleMax = ReasoningEffortOptions.encode(ProviderType.DeepSeek, "xhigh")
        assertEquals("max", compatibleMax.reasoningEffort)
    }

    @Test
    fun geminiUsesExtraBodyThinkingConfig() {
        val noneConfig = ReasoningEffortOptions.encode(ProviderType.Gemini, "none")
            .topLevel["extra_body"] as Map<*, *>
        val noneGoogle = noneConfig["google"] as Map<*, *>
        assertEquals(mapOf("thinking_budget" to 0), noneGoogle["thinking_config"])

        val highConfig = ReasoningEffortOptions.encode(ProviderType.Gemini, "high")
            .topLevel["extra_body"] as Map<*, *>
        val highGoogle = highConfig["google"] as Map<*, *>
        assertEquals(
            mapOf("thinking_level" to "high", "include_thoughts" to true),
            highGoogle["thinking_config"]
        )
        assertNull(ReasoningEffortOptions.encode(ProviderType.Gemini, "high").reasoningEffort)
    }

    @Test
    fun qwenUsesEnableThinkingFlag() {
        val off = ReasoningEffortOptions.encode(ProviderType.Qwen, "off")
        assertNull(off.reasoningEffort)
        assertEquals(false, off.topLevel["enable_thinking"])

        val on = ReasoningEffortOptions.encode(ProviderType.Qwen, "on")
        assertEquals(true, on.topLevel["enable_thinking"])
    }

    @Test
    fun claudeUsesAdaptiveThinkingAndMaxTokens() {
        val on = ReasoningEffortOptions.encode(ProviderType.Claude, "on")
        assertNull(on.reasoningEffort)
        assertEquals(mapOf("type" to "adaptive"), on.topLevel["thinking"])
        assertEquals(mapOf("effort" to "high"), on.topLevel["output_config"])
        assertEquals(8192, on.topLevel["max_tokens"])

        val off = ReasoningEffortOptions.encode(ProviderType.Claude, "off")
        assertNull(off.topLevel["thinking"])
        assertEquals(8192, off.topLevel["max_tokens"])
    }

    @Test
    fun defaultKeyMatchesFirstUsableTier() {
        assertEquals("high", ReasoningEffortOptions.defaultKeyFor(ProviderType.Generic))
        assertEquals("high", ReasoningEffortOptions.defaultKeyFor(ProviderType.DeepSeek))
        assertEquals("high", ReasoningEffortOptions.defaultKeyFor(ProviderType.Gemini))
        assertEquals("on", ReasoningEffortOptions.defaultKeyFor(ProviderType.Qwen))
        assertEquals("on", ReasoningEffortOptions.defaultKeyFor(ProviderType.Claude))
    }
}
