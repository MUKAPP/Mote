package com.mukapp.mote.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatApiClientUsageTest {

    @Test
    fun parseTokenUsageSupportsInputAndOutputTokenNames() {
        val usage = ChatApiClient.parseTokenUsage(
            JSONObject(
                """
                {
                  "input_tokens": 113,
                  "input_tokens_details": {
                    "cached_tokens": 0
                  },
                  "output_tokens": 248,
                  "output_tokens_details": {
                    "reasoning_tokens": 109
                  },
                  "total_tokens": 361
                }
                """.trimIndent()
            )
        )

        assertEquals(113, usage?.inputTokens)
        assertEquals(248, usage?.outputTokens)
        assertEquals(361, usage?.totalTokens)
        assertEquals(0, usage?.cachedInputTokens)
        assertEquals(109, usage?.reasoningOutputTokens)
    }

    @Test
    fun parseTokenUsageSupportsOpenAiLegacyNames() {
        val usage = ChatApiClient.parseTokenUsage(
            JSONObject(
                """
                {
                  "prompt_tokens": "21",
                  "prompt_tokens_details": {
                    "cached_tokens": "5"
                  },
                  "completion_tokens": 34,
                  "completion_tokens_details": {
                    "reasoning_tokens": "8"
                  },
                  "total_tokens": 55
                }
                """.trimIndent()
            )
        )

        assertEquals(21, usage?.inputTokens)
        assertEquals(34, usage?.outputTokens)
        assertEquals(55, usage?.totalTokens)
        assertEquals(5, usage?.cachedInputTokens)
        assertEquals(8, usage?.reasoningOutputTokens)
    }

    @Test
    fun parseTokenUsageIgnoresEmptyOrInvalidUsage() {
        assertNull(ChatApiClient.parseTokenUsage(null))
        assertNull(ChatApiClient.parseTokenUsage(JSONObject("{\"input_tokens\": -1, \"total_tokens\": \"bad\"}")))
    }
}
