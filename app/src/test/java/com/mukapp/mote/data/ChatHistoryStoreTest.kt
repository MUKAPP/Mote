package com.mukapp.mote.data

import com.mukapp.mote.data.model.AiToolCall
import com.mukapp.mote.data.model.AssistantMarkdownPart
import com.mukapp.mote.data.model.AssistantToolPart
import com.mukapp.mote.data.model.ChatAttachment
import com.mukapp.mote.data.model.ChatAttachmentType
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.ContextSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHistoryStoreTest {

    @Test
    fun serializeMessagesRoundTripsContextSummaryAndToolProtocol() {
        val messages = listOf(
            ChatMessage(
                id = "summary",
                role = ChatRole.System,
                content = "压缩摘要",
                isContextSummary = true,
                contextSummarySourceIds = listOf("u1", "a1", "t1")
            ),
            ChatMessage(
                id = "u2",
                role = ChatRole.User,
                content = "继续",
                attachments = listOf(
                    ChatAttachment(
                        id = "att_file",
                        type = ChatAttachmentType.File,
                        displayName = "notes.txt",
                        mimeType = "text/plain",
                        path = "content://docs/notes.txt",
                        textContent = "文件内容",
                        truncated = true
                    ),
                    ChatAttachment(
                        id = "att_image",
                        type = ChatAttachmentType.Image,
                        displayName = "photo.png",
                        mimeType = "image/png",
                        path = "/sdcard/photo.png",
                        base64Data = "aW1n"
                    )
                )
            ),
            ChatMessage(
                id = "a2",
                role = ChatRole.Assistant,
                content = "",
                toolCalls = listOf(AiToolCall(id = "call_2", name = "read_file", arguments = "{\"path\":\"/sdcard/a.txt\"}")),
                assistantParts = listOf(
                    AssistantMarkdownPart(id = "part_text", text = "我会读取文件"),
                    AssistantToolPart(
                        id = "part_tool",
                        toolName = "read_file",
                        toolArguments = "{\"path\":\"/sdcard/a.txt\"}",
                        result = "{\"ok\":true}"
                    )
                )
            ),
            ChatMessage(
                id = "t2",
                role = ChatRole.Tool,
                content = "{\"ok\":true}",
                toolCallId = "call_2",
                toolName = "read_file",
                toolArguments = "{\"path\":\"/sdcard/a.txt\"}"
            )
        )

        val restored = ChatHistoryStore.deserializeMessages(ChatHistoryStore.serializeMessages(messages))

        assertEquals(messages, restored)
    }

    @Test
    fun serializeContextSummariesRoundTripsSeparately() {
        val summaries = listOf(
            ContextSummary(
                id = "s1",
                content = "压缩摘要",
                sourceMessageIds = listOf("u1", "a1", "u1"),
                createdAt = 100L,
                updatedAt = 200L
            )
        )

        val restored = ChatHistoryStore.deserializeContextSummaries(
            ChatHistoryStore.serializeContextSummaries(summaries)
        )

        assertEquals(
            listOf(
                ContextSummary(
                    id = "s1",
                    content = "压缩摘要",
                    sourceMessageIds = listOf("u1", "a1"),
                    createdAt = 100L,
                    updatedAt = 200L
                )
            ),
            restored
        )
    }

    @Test
    fun serializeMessagesDoesNotPersistLoadingToolPartState() {
        val messages = listOf(
            ChatMessage(
                id = "a1",
                role = ChatRole.Assistant,
                content = "",
                assistantParts = listOf(
                    AssistantToolPart(
                        id = "call_1",
                        toolName = "read_file",
                        toolArguments = "{}",
                        result = "",
                        isLoading = true
                    )
                )
            )
        )

        val restored = ChatHistoryStore.deserializeMessages(ChatHistoryStore.serializeMessages(messages))
        val restoredToolPart = restored.single().assistantParts.single() as AssistantToolPart

        assertEquals(false, restoredToolPart.isLoading)
        assertEquals("", restoredToolPart.result)
    }
}
