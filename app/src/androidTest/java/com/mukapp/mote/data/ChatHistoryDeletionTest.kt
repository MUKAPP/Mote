package com.mukapp.mote.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ChatHistoryDeletionTest {
    private lateinit var root: File
    private lateinit var isolatedContext: Context
    private val historyDir: File get() = File(root, "chat_history")
    private val conversationsDir: File get() = File(historyDir, "conversations")
    private val indexFile: File get() = File(historyDir, "index.json")

    @Before
    fun setUp() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(targetContext.cacheDir, "history-deletion-${UUID.randomUUID()}")
        assertTrue(root.mkdirs())
        isolatedContext = object : ContextWrapper(targetContext) {
            override fun getFilesDir(): File = root
        }
        ChatHistoryStore.clearConversation(isolatedContext)
    }

    @After
    fun tearDown() {
        try {
            ChatHistoryStore.clearConversation(isolatedContext)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun normalDeletionReturnsExistingReplacementAndRepeatedDeletionIsIdempotent() {
        val first = save("A", "第一条消息")
        val second = save("B", "第二条消息")
        ChatHistoryStore.saveCurrentConversationId(isolatedContext, "A")

        val result = ChatHistoryStore.deleteConversation(isolatedContext, "A")

        assertFalse(result.cleanupFailed)
        assertEquals("B", result.replacementId)
        assertFalse(first.exists())
        assertTrue(second.isFile)
        assertEquals("B", ChatHistoryStore.loadCurrentConversation(isolatedContext).conversationId)
        assertEquals(listOf("B"), ChatHistoryStore.listConversations(isolatedContext).map { it.id })
        val repeated = ChatHistoryStore.deleteConversation(isolatedContext, "A")
        assertFalse(repeated.cleanupFailed)
        assertEquals("B", repeated.replacementId)
    }

    @Test
    fun unlinkFailureLeavesCompleteConversationIntact() {
        val file = save("A", "不可丢失的用户消息")
        val original = file.readText(Charsets.UTF_8)
        try {
            denyDirectoryWrites(conversationsDir)
            try {
                ChatHistoryStore.deleteConversation(isolatedContext, "A")
                fail("不可写目录中的对话不应删除成功")
            } catch (_: IOException) {
                // JSON unlink 尚未提交。
            }
            assertTrue(file.isFile)
            assertEquals(original, file.readText(Charsets.UTF_8))
            val state = ChatHistoryStore.loadConversation(isolatedContext, "A")
            assertNotNull(state)
            assertEquals("不可丢失的用户消息", state!!.uiMessages.single().content)
        } finally {
            restoreDirectoryWrites(conversationsDir)
        }
    }

    @Test
    fun directoryAtIndexPathReportsCommittedDeletionWithoutOverwritingMarker() {
        val file = save("A", "已提交的消息")
        ChatHistoryStore.saveCurrentConversationId(isolatedContext, "A")
        assertTrue(indexFile.delete())
        assertTrue(indexFile.mkdir())
        val marker = File(indexFile, "marker")
        marker.writeText("保留损坏索引证据", Charsets.UTF_8)

        val result = ChatHistoryStore.deleteConversation(isolatedContext, "A")

        assertTrue(result.cleanupFailed)
        assertFalse(file.exists())
        assertTrue(indexFile.isDirectory)
        assertEquals("保留损坏索引证据", marker.readText(Charsets.UTF_8))
        assertTrue(ChatHistoryStore.listConversations(isolatedContext).isEmpty())
    }

    @Test
    fun malformedIndexIsNotTreatedAsEmptyAfterDeletionCommits() {
        val file = save("A", "已提交的消息")
        val malformed = "{invalid-current-index"
        indexFile.writeText(malformed, Charsets.UTF_8)

        val result = ChatHistoryStore.deleteConversation(isolatedContext, "A")

        assertTrue(result.cleanupFailed)
        assertFalse(file.exists())
        assertEquals(malformed, indexFile.readText(Charsets.UTF_8))
    }

    @Test
    fun currentIndexWriteFailureDoesNotRollBackCommittedUnlink() {
        val file = save("A", "已提交的消息")
        save("B", "替代消息")
        ChatHistoryStore.saveCurrentConversationId(isolatedContext, "A")
        val originalIndex = indexFile.readText(Charsets.UTF_8)
        try {
            denyDirectoryWrites(historyDir)
            assumeTrue("conversations 子目录必须仍可写", conversationsDir.canWrite())

            val result = ChatHistoryStore.deleteConversation(isolatedContext, "A")

            assertTrue(result.cleanupFailed)
            assertEquals("B", result.replacementId)
            assertFalse(file.exists())
            assertEquals(originalIndex, indexFile.readText(Charsets.UTF_8))
            assertEquals(listOf("B"), ChatHistoryStore.listConversations(isolatedContext).map { it.id })
        } finally {
            restoreDirectoryWrites(historyDir)
        }
    }

    private fun save(id: String, content: String): File {
        val message = ChatMessage(role = ChatRole.User, content = content)
        return ChatHistoryStore.saveConversation(
            context = isolatedContext,
            settings = ApiSettings(),
            conversationId = id,
            title = "标题$id",
            uiMessages = listOf(message),
            conversationMessages = listOf(message)
        )
    }

    private fun denyDirectoryWrites(directory: File) {
        assumeTrue("文件系统不支持取消目录写权限", directory.setWritable(false, false))
        assumeTrue("目录必须仍可读且可执行", directory.canRead() && directory.canExecute())
        // 部分设备（如 root 环境）忽略权限位；明确跳过，不把故障未注入伪装成通过。
        val probe = File(directory, "permission-probe-${UUID.randomUUID()}")
        val writeDenied = try {
            probe.writeText("probe", Charsets.UTF_8)
            false
        } catch (_: IOException) {
            true
        } finally {
            probe.delete()
        }
        assumeTrue("设备文件系统未执行目录写权限限制", writeDenied)
    }

    private fun restoreDirectoryWrites(directory: File) {
        assertTrue("无法恢复隔离测试目录权限", directory.setWritable(true, true))
    }
}
