package com.mukapp.mote.ui

import com.mukapp.mote.data.model.AiToolCall
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.AssistantMarkdownPart
import com.mukapp.mote.data.model.AssistantToolPart
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.ContextSummary
import com.mukapp.mote.data.model.TokenUsage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConversationContextHelperTest {

    @Test
    fun longJsonToolResultKeepsFailureStatusWhenTruncated() {
        val longContent = "权限错误".repeat(9_000)
        val original = JSONObject()
            .put("ok", false)
            .put("exit_code", 2)
            .put("exitCode", 2)
            .put("cancelled", true)
            .put("needs_confirmation", true)
            .put("confirmation_id", "confirm_1234")
            .put("error", "权限被拒绝")
            .put("message", "原始工具结果失败")
            .put("content", longContent)
            .toString()
        val message = ChatMessage(
            role = ChatRole.Tool,
            content = original,
            toolCallId = "call_1",
            toolName = "shell"
        )

        val limited = ChatConversationContextHelper.limitToolResultForContext(message)
        val payload = JSONObject(limited.content)

        assertEquals(false, payload.getBoolean("ok"))
        assertEquals(2, payload.getInt("exit_code"))
        assertEquals(2, payload.getInt("exitCode"))
        assertEquals(true, payload.getBoolean("cancelled"))
        assertEquals(true, payload.getBoolean("needs_confirmation"))
        assertEquals("confirm_1234", payload.getString("confirmation_id"))
        assertEquals("权限被拒绝", payload.getString("error"))
        assertEquals("原始工具结果失败", payload.getString("original_message"))
        assertEquals(true, payload.getBoolean("truncated"))
        assertEquals("json_object", payload.getString("original_format"))
        assertEquals(original.length, payload.getInt("original_chars"))
        assertEquals(
            original.length - ChatConversationContextHelper.MaxToolResultContextChars,
            payload.getInt("omitted_chars")
        )
        assertEquals(ChatConversationContextHelper.MaxToolResultContextChars / 2, payload.getString("head").length)
        assertEquals(ChatConversationContextHelper.MaxToolResultContextChars / 2, payload.getString("tail").length)
        assertFalse(payload.has("content"))
    }

    @Test
    fun longPlainTextToolResultDoesNotDeclareSuccessWhenTruncated() {
        val original = "x".repeat(ChatConversationContextHelper.MaxToolResultContextChars + 1)
        val message = ChatMessage(role = ChatRole.Tool, content = original)

        val limited = ChatConversationContextHelper.limitToolResultForContext(message)
        val payload = JSONObject(limited.content)

        assertFalse(payload.has("ok"))
        assertEquals(true, payload.getBoolean("truncated"))
        assertEquals("text", payload.getString("original_format"))
    }

    @Test
    fun limitToolResultsForContextReturnsTruncatedCopiesWithoutMutatingOriginals() {
        val original = "x".repeat(ChatConversationContextHelper.MaxToolResultContextChars + 1)
        val shortContent = "短结果"
        val longToolMessage = ChatMessage(
            id = "t1",
            role = ChatRole.Tool,
            content = original,
            toolCallId = "call_1",
            toolName = "read_file"
        )
        val shortToolMessage = ChatMessage(
            id = "t2",
            role = ChatRole.Tool,
            content = shortContent,
            toolCallId = "call_2",
            toolName = "list_path"
        )

        val limited = ChatConversationContextHelper.limitToolResultsForContext(
            listOf(longToolMessage, shortToolMessage)
        )

        assertEquals(original, longToolMessage.content)
        assertEquals(shortContent, shortToolMessage.content)
        assertEquals(2, limited.size)
        assertTrue(JSONObject(limited[0].content).getBoolean("truncated"))
        assertEquals(shortToolMessage, limited[1])
    }

    @Test
    fun replaceLoadingToolPartsFillsFullResultBeforeLaterContextCompression() {
        val fullResult = "完整工具结果".repeat(2_000)
        val parts = listOf(
            AssistantMarkdownPart(id = "text_1", text = "先读取文件"),
            AssistantToolPart(
                id = "call_1",
                toolName = "read_file",
                toolArguments = "{}",
                result = "",
                isLoading = true
            )
        )
        val toolResult = ChatMessage(
            role = ChatRole.Tool,
            content = fullResult,
            toolCallId = "call_1",
            toolName = "read_file"
        )

        val replaced = ChatConversationContextHelper.replaceLoadingToolParts(parts, listOf(toolResult))
        val replacedToolPart = replaced[1] as AssistantToolPart

        assertEquals(parts[0], replaced[0])
        assertEquals(false, replacedToolPart.isLoading)
        assertEquals(fullResult, replacedToolPart.result)
    }

    @Test
    fun requireWithinModelContextLengthThrowsForOversizedSingleUserTurn() {
        val messages = listOf(
            ChatMessage(id = "u1", role = ChatRole.User, content = "读取大文件"),
            ChatMessage(
                id = "a1",
                role = ChatRole.Assistant,
                content = "",
                toolCalls = listOf(AiToolCall(id = "call_1", name = "read_file", arguments = "{}"))
            ),
            ChatMessage(
                id = "t1",
                role = ChatRole.Tool,
                content = "x".repeat(1_000),
                toolCallId = "call_1",
                toolName = "read_file"
            )
        )
        val estimatedTokens = ChatConversationContextHelper.estimateConversationTokens(messages)

        assertTrue(estimatedTokens > 10)
        assertThrows(IllegalStateException::class.java) {
            ChatConversationContextHelper.requireWithinModelContextLength(
                settings = ApiSettings(modelContextLength = 10),
                contextTokens = estimatedTokens
            )
        }
    }

    @Test
    fun resolveConversationTokenCountUsesApiUsageWhenMessagesMatchAnchor() {
        val messages = listOf(
            ChatMessage(id = "u1", role = ChatRole.User, content = "你好"),
            ChatMessage(id = "a1", role = ChatRole.Assistant, content = "你好，有什么可以帮你？")
        )
        val anchor = ChatConversationContextHelper.buildTokenUsageAnchor(
            messages = messages,
            usage = TokenUsage(inputTokens = 113)
        )

        val tokenCount = ChatConversationContextHelper.resolveConversationTokenCount(messages, anchor)

        assertEquals(113, tokenCount.tokens)
        assertEquals("API 返回", tokenCount.sourceLabel)
    }

    @Test
    fun resolveConversationTokenCountAddsEstimatedTokensForAppendedMessages() {
        val anchoredMessages = listOf(
            ChatMessage(id = "u1", role = ChatRole.User, content = "列目录")
        )
        val appendedMessage = ChatMessage(id = "a1", role = ChatRole.Assistant, content = "目录为空")
        val anchor = ChatConversationContextHelper.buildTokenUsageAnchor(
            messages = anchoredMessages,
            usage = TokenUsage(inputTokens = 50)
        )

        val tokenCount = ChatConversationContextHelper.resolveConversationTokenCount(
            messages = anchoredMessages + appendedMessage,
            usageAnchor = anchor
        )

        assertEquals(
            50 + ChatConversationContextHelper.estimateConversationTokens(listOf(appendedMessage)),
            tokenCount.tokens
        )
        assertEquals("API 返回 + 增量估算", tokenCount.sourceLabel)
    }

    @Test
    fun resolveConversationTokenCountFallsBackToEstimateWhenAnchorDoesNotMatch() {
        val anchorMessages = listOf(ChatMessage(id = "u1", role = ChatRole.User, content = "旧问题"))
        val currentMessages = listOf(ChatMessage(id = "u2", role = ChatRole.User, content = "新问题"))
        val anchor = ChatConversationContextHelper.buildTokenUsageAnchor(
            messages = anchorMessages,
            usage = TokenUsage(inputTokens = 10_000)
        )

        val tokenCount = ChatConversationContextHelper.resolveConversationTokenCount(currentMessages, anchor)

        assertEquals(ChatConversationContextHelper.estimateConversationTokens(currentMessages), tokenCount.tokens)
        assertEquals("本地估算", tokenCount.sourceLabel)
    }

    @Test
    fun rebuildWithoutSummaryKeepsApiToolProtocol() {
        val user = ChatMessage(id = "u1", role = ChatRole.User, content = "列目录")
        val toolCallAssistant = ChatMessage(
            id = "a1",
            role = ChatRole.Assistant,
            content = "",
            toolCalls = listOf(AiToolCall(id = "call_1", name = "list_path", arguments = "{}"))
        )
        val tool = ChatMessage(
            id = "t1",
            role = ChatRole.Tool,
            content = "{\"ok\":true}",
            toolCallId = "call_1",
            toolName = "list_path"
        )
        val finalAssistant = ChatMessage(id = "a2", role = ChatRole.Assistant, content = "目录为空")
        val uiMessages = listOf(
            user,
            ChatMessage(id = "ui_a", role = ChatRole.Assistant, content = "目录为空")
        )

        val rebuilt = ChatConversationContextHelper.rebuildConversationAfterUiMutation(
            uiMessages = uiMessages,
            conversationMessages = listOf(user, toolCallAssistant, tool, finalAssistant),
            affectedUserMessageIds = emptySet()
        )

        assertEquals(listOf("u1", "a1", "t1", "a2"), rebuilt.map { it.id })
        assertEquals("call_1", rebuilt[2].toolCallId)
        assertEquals(1, rebuilt[1].toolCalls.size)
    }

    @Test
    fun rebuildWhenSummarySourceChangesDropsUnrecoverableSummaryButKeepsCurrentApiTurn() {
        val summary = ChatMessage(
            id = "summary",
            role = ChatRole.System,
            content = "历史摘要",
            isContextSummary = true,
            contextSummarySourceIds = listOf("u_old")
        )
        val oldUser = ChatMessage(id = "u_old", role = ChatRole.User, content = "旧问题")
        val currentUser = ChatMessage(id = "u2", role = ChatRole.User, content = "当前问题")
        val toolCallAssistant = ChatMessage(
            id = "a2",
            role = ChatRole.Assistant,
            content = "",
            toolCalls = listOf(AiToolCall(id = "call_2", name = "read_file", arguments = "{}"))
        )
        val tool = ChatMessage(
            id = "t2",
            role = ChatRole.Tool,
            content = "{\"ok\":true}",
            toolCallId = "call_2",
            toolName = "read_file"
        )

        val rebuilt = ChatConversationContextHelper.rebuildConversationAfterUiMutation(
            uiMessages = listOf(
                oldUser,
                ChatMessage(id = "old_ui_a", role = ChatRole.Assistant, content = "旧回答"),
                currentUser,
                ChatMessage(id = "current_ui_a", role = ChatRole.Assistant, content = "当前展示回答")
            ),
            conversationMessages = listOf(summary, currentUser, toolCallAssistant, tool),
            affectedUserMessageIds = setOf("u_old")
        )

        assertEquals(listOf("u2", "a2", "t2"), rebuilt.map { it.id })
        assertFalse(rebuilt.any { it.isContextSummary })
        assertEquals("call_2", rebuilt[2].toolCallId)
    }

    @Test
    fun rebuildRemovesEmbeddedSummaryAndKeepsLaterApiTurn() {
        val summary = ChatMessage(
            id = "summary",
            role = ChatRole.System,
            content = "历史摘要",
            isContextSummary = true,
            contextSummarySourceIds = listOf("u_old")
        )
        val deletedUser = ChatMessage(id = "u2", role = ChatRole.User, content = "删除这轮")
        val deletedTool = ChatMessage(id = "t2", role = ChatRole.Tool, content = "{\"ok\":true}")
        val laterUser = ChatMessage(id = "u3", role = ChatRole.User, content = "保留这轮")
        val laterAssistant = ChatMessage(id = "a3", role = ChatRole.Assistant, content = "保留回答")

        val rebuilt = ChatConversationContextHelper.rebuildConversationAfterUiMutation(
            uiMessages = listOf(
                laterUser,
                ChatMessage(id = "ui_a3", role = ChatRole.Assistant, content = "保留回答")
            ),
            conversationMessages = listOf(summary, deletedUser, deletedTool, laterUser, laterAssistant),
            affectedUserMessageIds = setOf("u2")
        )

        assertEquals(listOf("u3", "a3"), rebuilt.map { it.id })
        assertFalse(rebuilt.any { it.isContextSummary })
    }

    @Test
    fun filterContextSummariesDropsSummaryWhenRemovedIdsHitSourceIds() {
        val summaries = listOf(
            ContextSummary(id = "s1", content = "旧摘要", sourceMessageIds = listOf("u1", "a1")),
            ContextSummary(id = "s2", content = "其它摘要", sourceMessageIds = listOf("u2", "a2"))
        )

        val filtered = ChatConversationContextHelper.filterContextSummariesAfterMessagesRemoved(
            summaries = summaries,
            removedMessageIds = setOf("a1")
        )

        assertEquals(listOf("s2"), filtered.map { it.id })
    }

    @Test
    fun filterContextSummariesCascadesWhenSummaryDependsOnRemovedSummary() {
        val summaries = listOf(
            ContextSummary(id = "s1", content = "第一段摘要", sourceMessageIds = listOf("u1", "a1")),
            ContextSummary(id = "s2", content = "扩展摘要", sourceMessageIds = listOf("s1", "u2", "a2")),
            ContextSummary(id = "s3", content = "独立摘要", sourceMessageIds = listOf("u3", "a3"))
        )

        val filtered = ChatConversationContextHelper.filterContextSummariesAfterMessagesRemoved(
            summaries = summaries,
            removedMessageIds = setOf("a1")
        )

        assertEquals(listOf("s3"), filtered.map { it.id })
    }

    @Test
    fun applyContextSummariesForRequestReplacesCoveredOriginalMessages() {
        val messages = listOf(
            ChatMessage(id = "u1", role = ChatRole.User, content = "旧问题"),
            ChatMessage(id = "a1", role = ChatRole.Assistant, content = "旧回答"),
            ChatMessage(id = "u2", role = ChatRole.User, content = "继续"),
            ChatMessage(id = "a2", role = ChatRole.Assistant, content = "继续回答")
        )
        val summary = ContextSummary(
            id = "s1",
            content = "旧对话摘要",
            sourceMessageIds = listOf("u1", "a1")
        )

        val requestMessages = ChatConversationContextHelper.applyContextSummariesForRequest(
            conversationMessages = messages,
            contextSummaries = listOf(summary)
        )

        assertEquals(listOf("s1", "u2", "a2"), requestMessages.map { it.id })
        assertTrue(requestMessages.first().isContextSummary)
        assertEquals(ChatRole.User, requestMessages.first().role)
        assertTrue(requestMessages.first().content.contains("历史事实摘要"))
        assertTrue(requestMessages.first().content.contains("旧对话摘要"))
        assertEquals(listOf("u1", "a1"), requestMessages.first().contextSummarySourceIds)
        assertEquals(listOf("u1", "a1", "u2", "a2"), messages.map { it.id })
    }

    @Test
    fun applyContextSummariesForRequestKeepsLegacySummaryWhenSourcesAreMissing() {
        val messages = listOf(
            ChatMessage(id = "u2", role = ChatRole.User, content = "继续"),
            ChatMessage(id = "a2", role = ChatRole.Assistant, content = "继续回答")
        )
        val summary = ContextSummary(
            id = "s1",
            content = "旧对话摘要",
            sourceMessageIds = listOf("u1", "a1")
        )

        val requestMessages = ChatConversationContextHelper.applyContextSummariesForRequest(
            conversationMessages = messages,
            contextSummaries = listOf(summary)
        )

        assertEquals(listOf("s1", "u2", "a2"), requestMessages.map { it.id })
        assertTrue(requestMessages.first().isContextSummary)
    }

    @Test
    fun applyContextSummariesForRequestUsesOnlyLatestSummary() {
        val messages = listOf(
            ChatMessage(id = "u1", role = ChatRole.User, content = "旧问题 1"),
            ChatMessage(id = "a1", role = ChatRole.Assistant, content = "旧回答 1"),
            ChatMessage(id = "u2", role = ChatRole.User, content = "旧问题 2"),
            ChatMessage(id = "a2", role = ChatRole.Assistant, content = "旧回答 2"),
            ChatMessage(id = "u3", role = ChatRole.User, content = "继续")
        )
        val oldSummary = ContextSummary(
            id = "s_old",
            content = "第一段摘要",
            sourceMessageIds = listOf("u1", "a1")
        )
        val latestSummary = ContextSummary(
            id = "s_latest",
            content = "第一段和第二段摘要",
            sourceMessageIds = listOf("s_old", "u2", "a2")
        )

        val requestMessages = ChatConversationContextHelper.applyContextSummariesForRequest(
            conversationMessages = messages,
            contextSummaries = listOf(oldSummary, latestSummary)
        )

        assertEquals(listOf("s_latest", "u3"), requestMessages.map { it.id })
        assertEquals(
            listOf("s_old", "u2", "a2"),
            requestMessages.first().contextSummarySourceIds
        )
    }

    @Test
    fun applyContextSummariesForRequestFallsBackAfterLatestSummaryIsRemoved() {
        val messages = listOf(
            ChatMessage(id = "u1", role = ChatRole.User, content = "旧问题 1"),
            ChatMessage(id = "a1", role = ChatRole.Assistant, content = "旧回答 1"),
            ChatMessage(id = "u2", role = ChatRole.User, content = "旧问题 2")
        )
        val oldSummary = ContextSummary(
            id = "s_old",
            content = "第一段摘要",
            sourceMessageIds = listOf("u1", "a1")
        )

        val requestMessages = ChatConversationContextHelper.applyContextSummariesForRequest(
            conversationMessages = messages,
            contextSummaries = listOf(oldSummary)
        )

        assertEquals(listOf("s_old", "u2"), requestMessages.map { it.id })
    }
}
