package com.mukapp.mote.network

import com.mukapp.mote.data.model.ChatAttachment
import com.mukapp.mote.data.model.ChatAttachmentType
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatApiClientUsageTest {

    @Test
    fun buildApiMessageUsesImageContentPartsForImageAttachments() {
        val message = ChatMessage(
            role = ChatRole.User,
            content = "看看这张图",
            attachments = listOf(
                ChatAttachment(
                    type = ChatAttachmentType.Image,
                    displayName = "photo.png",
                    mimeType = "image/png",
                    path = "content://docs/photo.png",
                    base64Data = "aW1n"
                )
            )
        )

        val content = ChatApiClient.buildApiMessage(message).getJSONArray("content")

        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertTrue(content.getJSONObject(0).getString("text").contains("看看这张图"))
        assertTrue(content.getJSONObject(0).getString("text").contains("图片已作为 base64"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/png;base64,aW1n",
            content.getJSONObject(1).getJSONObject("image_url").getString("url")
        )
    }

    @Test
    fun buildApiMessageIncludesContentResolverTextForUnreadableTextFile() {
        val message = ChatMessage(
            role = ChatRole.User,
            content = "总结文件",
            attachments = listOf(
                ChatAttachment(
                    type = ChatAttachmentType.File,
                    displayName = "notes.txt",
                    mimeType = "text/plain",
                    path = "content://docs/notes.txt",
                    directReadable = false,
                    textContent = "第一行\n第二行",
                    truncated = true
                )
            )
        )

        val content = ChatApiClient.buildApiMessage(message).getString("content")

        assertTrue(content.contains("总结文件"))
        assertTrue(content.contains("路径：content://docs/notes.txt"))
        assertTrue(content.contains("第一行\n第二行"))
        assertTrue(content.contains("内容过长"))
    }

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

    @Test
    fun parseConversationTitleResponseKeepsLengthTruncatedTextOnly() {
        val title = ChatApiClient.parseConversationTitleResponse(
            """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "这是一个因为模型没有遵守长度要求而过长的标题"
                  },
                  "finish_reason": "length"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("这是一个因为模型没有遵守长度要求而过长的标题", title)
        assertFalse(title.contains("提示：模型输出因达到长度限制被截断"))
    }

    @Test
    fun parseConversationTitleResponseReturnsBlankWhenContentIsMissing() {
        val title = ChatApiClient.parseConversationTitleResponse(
            """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "reasoning_content": "我在思考标题"
                  },
                  "finish_reason": "stop"
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("", title)
    }
}
