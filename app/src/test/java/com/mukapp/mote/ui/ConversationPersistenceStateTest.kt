package com.mukapp.mote.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPersistenceStateTest {
    @Test
    fun failedDeletionRetainsUserTitleAndQueuedSave() {
        val state = ConversationPersistenceState()
        state.recordUserTitle("A", "手动标题")
        val save = state.registerSave("A")
        assertTrue(state.beginDelete("A"))
        assertTrue(state.isBlocked("A"))
        assertFalse(state.canApplySwitch("A", 4, 4))
        assertFalse(state.shouldSkipSave("A", save))
        assertTrue(state.shouldSkipGeneratedTitle("A"))

        state.rollbackDelete("A")

        assertFalse(state.isBlocked("A"))
        assertFalse(state.isDeleted("A"))
        assertTrue(state.canApplySwitch("A", 4, 4))
        assertFalse(state.shouldSkipSave("A", save))
        assertTrue(state.isUserRenamed("A"))
        assertEquals("手动标题", state.effectiveTitle("A", "旧快照"))
        assertTrue(state.shouldSkipGeneratedTitle("A"))
    }

    @Test
    fun pendingDeletionAllowsAlreadyQueuedWritesAndRollbackPreservesThem() {
        val state = ConversationPersistenceState()
        val save = state.registerSave("A")
        val rename = state.registerRename("A")
        assertTrue(state.beginDelete("A"))
        assertFalse(state.shouldSkipSave("A", save))
        assertTrue(state.isLatestRename("A", rename))
        assertFalse(state.shouldSkipGeneratedTitle("A"))
        state.recordGeneratedTitle("A", "生成标题")
        assertEquals("生成标题", state.effectiveTitle("A", "旧快照"))

        state.rollbackDelete("A")

        assertFalse(state.shouldSkipSave("A", save))
        assertTrue(state.isLatestRename("A", rename))
        assertEquals(RenameCompletion.Updated, state.finishRename("A", rename, true))
        state.finishSave("A", save)
        assertTrue(state.shouldSkipSave("A", save))
    }

    @Test
    fun committedDeletionBlocksLateWritesAndSwitchEvenWithoutNavigationVersionChange() {
        val state = ConversationPersistenceState()
        state.recordUserTitle("A", "手动标题")
        val save = state.registerSave("A")
        val rename = state.registerRename("A")
        assertTrue(state.beginDelete("A"))
        assertFalse(state.beginDelete("A"))
        state.completeDelete("A")

        assertTrue(state.isDeleted("A"))
        assertTrue(state.isBlocked("A"))
        assertTrue(state.shouldSkipSave("A", save))
        assertFalse(state.isLatestRename("A", rename))
        assertTrue(state.shouldSkipGeneratedTitle("A"))
        assertFalse(state.canApplySwitch("A", 7, 7))
        assertTrue(state.canApplySwitch("B", 7, 7))
        assertFalse(state.canApplySwitch("B", 7, 8))
        assertFalse(state.isUserRenamed("A"))
        assertEquals("快照标题", state.effectiveTitle("A", "快照标题"))
        assertEquals(RenameCompletion.Ignored, state.finishRename("A", rename, true))

        state.rollbackDelete("A")
        assertTrue(state.isDeleted("A"))
        assertFalse(state.beginDelete("A"))
        assertFalse(state.canApplySwitch("A", 8, 8))
    }

    @Test
    fun oldSaveCompletionCannotClearNewerSave() {
        val state = ConversationPersistenceState()
        val first = state.registerSave("A")
        val second = state.registerSave("A")
        assertTrue(state.shouldSkipSave("A", first))
        state.finishSave("A", first)
        assertFalse(state.shouldSkipSave("A", second))
        state.finishSave("A", second)
        assertTrue(state.shouldSkipSave("A", second))
    }

    @Test
    fun staleRenameFailureAndSuccessLeaveLatestRequestIntact() {
        val state = ConversationPersistenceState()
        val first = state.registerRename("A")
        val second = state.registerRename("A")
        assertFalse(state.isLatestRename("A", first))
        assertEquals(RenameCompletion.Ignored, state.finishRename("A", first, false))
        assertEquals(RenameCompletion.Ignored, state.finishRename("A", first, true))
        assertTrue(state.isLatestRename("A", second))
        assertEquals(RenameCompletion.Failed, state.finishRename("A", second, false))
        assertFalse(state.isLatestRename("A", second))
        val third = state.registerRename("A")
        assertEquals(RenameCompletion.Updated, state.finishRename("A", third, true))
    }

    @Test
    fun renameCompletionWhileDeletingIsIgnoredButCommittedTitleSurvivesRollback() {
        for (updated in listOf(false, true)) {
            val state = ConversationPersistenceState()
            val rename = state.registerRename("B")
            state.beginDelete("B")
            if (updated) state.recordUserTitle("B", "新标题")
            assertEquals(RenameCompletion.Ignored, state.finishRename("B", rename, updated))
            state.rollbackDelete("B")
            assertEquals(if (updated) "新标题" else "旧标题", state.effectiveTitle("B", "旧标题"))
            assertEquals(updated, state.isUserRenamed("B"))
            assertFalse(state.isLatestRename("B", rename))
        }
    }

    @Test
    fun generatedTitleOverridesSnapshotUntilDeletionAndOtherIdsStayIndependent() {
        val state = ConversationPersistenceState()
        state.recordGeneratedTitle("A", "生成标题")
        state.recordUserTitle("B", "用户标题")
        state.beginDelete("A")
        state.completeDelete("A")
        assertEquals("原始标题", state.effectiveTitle("A", "原始标题"))
        assertEquals("用户标题", state.effectiveTitle("B", "原始标题"))
        assertTrue(state.shouldSkipGeneratedTitle("B"))
        assertTrue(state.canApplySwitch("B", 1, 1))
    }
}
