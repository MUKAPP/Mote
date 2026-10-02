package com.mukapp.mote.ui

internal enum class RenameCompletion { Ignored, Updated, Failed }

/** 跨持久化协程的版本、标题和删除状态；不在此锁内执行 I/O。 */
internal class ConversationPersistenceState {
    private enum class DeletePhase { Pending, Deleted }

    private val lock = Any()
    private val saveVersions = mutableMapOf<String, Long>()
    private val renameVersions = mutableMapOf<String, Long>()
    private val titles = mutableMapOf<String, String>()
    private val userRenamedIds = mutableSetOf<String>()
    private val deletionPhases = mutableMapOf<String, DeletePhase>()
    private var nextSaveVersion = 0L
    private var nextRenameVersion = 0L

    fun registerSave(id: String): Long = synchronized(lock) {
        (++nextSaveVersion).also { saveVersions[id] = it }
    }

    fun shouldSkipSave(id: String, version: Long): Boolean = synchronized(lock) {
        deletionPhases[id] == DeletePhase.Deleted || saveVersions[id] != version
    }

    fun finishSave(id: String, version: Long) = synchronized(lock) {
        if (saveVersions[id] == version) saveVersions.remove(id)
        Unit
    }

    fun registerRename(id: String): Long = synchronized(lock) {
        (++nextRenameVersion).also { renameVersions[id] = it }
    }

    fun isLatestRename(id: String, version: Long): Boolean = synchronized(lock) {
        deletionPhases[id] != DeletePhase.Deleted && renameVersions[id] == version
    }

    fun finishRename(id: String, version: Long, updated: Boolean): RenameCompletion = synchronized(lock) {
        if (renameVersions[id] != version) return@synchronized RenameCompletion.Ignored
        renameVersions.remove(id)
        when {
            id in deletionPhases -> RenameCompletion.Ignored
            updated -> RenameCompletion.Updated
            else -> RenameCompletion.Failed
        }
    }

    fun recordUserTitle(id: String, title: String) = synchronized(lock) {
        titles[id] = title
        userRenamedIds += id
    }

    fun recordGeneratedTitle(id: String, title: String) = synchronized(lock) {
        titles[id] = title
    }

    fun effectiveTitle(id: String, snapshotTitle: String): String = synchronized(lock) {
        titles[id] ?: snapshotTitle
    }

    fun isUserRenamed(id: String): Boolean = synchronized(lock) { id in userRenamedIds }

    fun shouldSkipGeneratedTitle(id: String): Boolean = synchronized(lock) {
        deletionPhases[id] == DeletePhase.Deleted || id in userRenamedIds
    }

    fun beginDelete(id: String): Boolean = synchronized(lock) {
        if (id in deletionPhases) return@synchronized false
        deletionPhases[id] = DeletePhase.Pending
        true
    }

    fun rollbackDelete(id: String) = synchronized(lock) {
        if (deletionPhases[id] == DeletePhase.Pending) deletionPhases.remove(id)
        Unit
    }

    fun completeDelete(id: String) = synchronized(lock) {
        deletionPhases[id] = DeletePhase.Deleted
        saveVersions.remove(id)
        renameVersions.remove(id)
        titles.remove(id)
        userRenamedIds.remove(id)
        Unit
    }

    fun isBlocked(id: String): Boolean = synchronized(lock) { id in deletionPhases }

    fun isDeleted(id: String): Boolean = synchronized(lock) { deletionPhases[id] == DeletePhase.Deleted }

    fun canApplySwitch(id: String, expectedVersion: Long, actualVersion: Long): Boolean = synchronized(lock) {
        expectedVersion == actualVersion && id !in deletionPhases
    }
}
