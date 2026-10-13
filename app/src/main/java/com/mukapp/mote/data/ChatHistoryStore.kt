package com.mukapp.mote.data

import android.content.Context
import com.mukapp.mote.data.model.AssistantMarkdownPart
import com.mukapp.mote.data.model.AssistantPart
import com.mukapp.mote.data.model.AssistantThinkingPart
import com.mukapp.mote.data.model.AssistantToolPart
import com.mukapp.mote.data.model.AiToolCall
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ChatAttachment
import com.mukapp.mote.data.model.ChatAttachmentType
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.data.model.ConversationSummary
import com.mukapp.mote.data.model.ContextSummary
import com.mukapp.mote.data.model.SavedConversationState
import com.mukapp.mote.data.model.resolvedChatModel
import com.mukapp.mote.util.MoteLog
import com.mukapp.mote.util.toChatRoleOrNull
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal class AttachmentPreparationBatch(
    val id: String,
    val rootPath: String,
    val conversationId: String,
    val sourceAttachments: List<ChatAttachment>,
    val expectedRefs: Set<String>
) {
    internal val createdRefs = mutableSetOf<String>()
    internal var committedMessageId: String? = null
    internal var finished: Boolean = false
}

object ChatHistoryStore {
    private const val Component = "History"
    private const val SchemaVersion = 3
    private const val DirectoryName = "chat_history"
    private const val ConversationsDirectoryName = "conversations"
    private const val IndexFileName = "index.json"
    private const val SummaryIndexFileName = "summaries.json"
    private const val SummaryIndexSchemaVersion = 2
    private const val BlobsDirectoryName = "blobs"
    private const val BlobFileExtension = "b64"
    private const val LegacyFileName = "history.json"
    private const val LegacyMigrationMarkerFileName = "legacy_migrated.json"
    private const val CorruptedDirectoryName = "corrupted"
    private const val DefaultConversationTitle = "新对话"
    private val SafeConversationIdPattern = Regex("^[A-Za-z0-9_-]{1,80}$")

    private data class SummaryIndexEntry(
        val summary: ConversationSummary,
        val fileMtime: Long,
        val fileLength: Long
    )

    private val summaryCacheLock = Any()

    /**
     * 对话数据变更互斥锁：串行化 saveConversation / updateConversationTitle /
     * deleteConversation / clearConversation，避免并发调用方对同一对话文件读-改-写
     * 交错产生 last-writer-wins（当前 ViewModel 侧已由 persistenceMutex 串行，
     * 此锁在 store 层收口该约束）。锁序：mutation → summaryCacheLock / pinLock；
     * pinLock 不反向获取磁盘锁，旧历史迁移同样先获取 mutation。
     */
    private val conversationMutationLock = Any()

    // 需要同时持有两把锁时始终 mutation → pin；Main 只操作 pin 元数据。
    private val pinLock = Any()
    private data class PinKey(val rootPath: String, val conversationId: String, val ownerId: String)
    private val attachmentPins = mutableMapOf<PinKey, Set<String>>()
    private val preparationBatches = mutableMapOf<PinKey, AttachmentPreparationBatch>()
    private val retainedMessageIds = mutableMapOf<PinKey, Set<String>>()

    internal fun attachmentRefs(attachments: List<ChatAttachment>): Set<String> =
        attachments.mapNotNull { it.base64Ref?.takeIf(::isSafeBlobFileName) }.toSet()

    internal fun updateAttachmentPins(context: Context, conversationId: String, ownerId: String, refs: Set<String>) {
        synchronized(pinLock) {
            val key = PinKey(context.filesDir.absolutePath, conversationId, ownerId)
            val safeRefs = refs.filterTo(mutableSetOf(), ::isSafeBlobFileName)
            if (safeRefs.isEmpty()) attachmentPins.remove(key) else attachmentPins[key] = safeRefs
        }
    }

    // 只在 pinLock 内使用。
    private fun isRefPinned(rootPath: String, conversationId: String, ref: String): Boolean =
        attachmentPins.any { (key, refs) -> key.rootPath == rootPath && key.conversationId == conversationId && ref in refs }

    private fun hasPins(rootPath: String, conversationId: String): Boolean =
        attachmentPins.keys.any { it.rootPath == rootPath && it.conversationId == conversationId }

    internal fun beginAttachmentPreparation(context: Context, conversationId: String, attachments: List<ChatAttachment>): AttachmentPreparationBatch {
        require(isSafeConversationId(conversationId)) { "对话 ID 不合法。" }
        val expectedRefs = attachments.filter { it.type == ChatAttachmentType.Image }.mapNotNull { attachment ->
            attachment.base64Ref?.takeIf(::isSafeBlobFileName)
                ?: attachment.id.takeIf { !attachment.base64Data.isNullOrBlank() && SafeConversationIdPattern.matches(it) }
                    ?.let { "$it.$BlobFileExtension" }
        }.toSet() + attachmentRefs(attachments)
        val batch = AttachmentPreparationBatch(UUID.randomUUID().toString(), context.filesDir.absolutePath, conversationId, attachments.toList(), expectedRefs)
        synchronized(pinLock) {
            val key = PinKey(batch.rootPath, conversationId, batch.id)
            preparationBatches[key] = batch
            attachmentPins[key] = expectedRefs
        }
        return batch
    }

    internal fun storeAttachmentPayloads(context: Context, batch: AttachmentPreparationBatch, limitBytes: Long = 0): List<ChatAttachment> = synchronized(conversationMutationLock) {
        check(context.filesDir.absolutePath == batch.rootPath)
        synchronized(pinLock) {
            check(preparationBatches[PinKey(batch.rootPath, batch.conversationId, batch.id)] === batch && !batch.finished)
        }
        val blobDir = conversationBlobDir(context, batch.conversationId)
        val newPayloads = linkedMapOf<String, String>()
        val prepared = batch.sourceAttachments.filter { it.type == ChatAttachmentType.Image }.distinctBy { it.id }.associate { attachment ->
            if (attachment.base64Ref != null) {
                check(isSafeBlobFileName(attachment.base64Ref)) { "附件内容缺失或不可读，请重新选择图片。" }
                attachment.id to attachment.copy(base64Data = null)
            } else {
                val base64 = attachment.base64Data?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("附件内容缺失或不可读，请重新选择图片。")
                check(SafeConversationIdPattern.matches(attachment.id)) { "附件 ID 不合法。" }
                val ref = "${attachment.id}.$BlobFileExtension"
                if (!File(blobDir, ref).exists()) newPayloads[ref] = base64
                attachment.id to attachment.copy(base64Data = null, base64Ref = ref)
            }
        }
        if (limitBytes > 0 && newPayloads.isNotEmpty()) {
            val usage = scanStorage(File(File(context.filesDir, DirectoryName), BlobsDirectoryName), attachmentsOnly = true)
            check(usage.complete) { "无法统计附件占用，暂时不能新增图片。" }
            val newBytes = newPayloads.values.sumOf { it.length.toLong() }
            check(newBytes <= limitBytes && usage.attachmentBytes <= limitBytes - newBytes) {
                "附件存储将超过设置的上限，请删除不需要的对话或调整上限。"
            }
        }
        newPayloads.forEach { (ref, base64) ->
            if (Thread.currentThread().isInterrupted) throw CancellationException("附件准备已取消。")
            writeBlobAtomically(File(blobDir, ref), base64) {
                synchronized(pinLock) { batch.createdRefs += ref }
            }
        }
        batch.sourceAttachments.map { attachment ->
            if (attachment.type == ChatAttachmentType.Image) prepared.getValue(attachment.id) else attachment
        }
    }

    internal fun commitAttachmentPreparation(batch: AttachmentPreparationBatch, messageId: String) {
        synchronized(pinLock) {
            check(!batch.finished)
            batch.committedMessageId = messageId
        }
    }

    internal fun finishAttachmentPreparation(context: Context, batch: AttachmentPreparationBatch, handedOff: Boolean) {
        synchronized(conversationMutationLock) {
            val rollbackRefs = synchronized(pinLock) {
                if (batch.finished) return
                batch.finished = true
                if (handedOff && batch.committedMessageId != null) {
                    null
                } else {
                    val key = PinKey(batch.rootPath, batch.conversationId, batch.id)
                    attachmentPins.remove(key)
                    preparationBatches.remove(key)
                    batch.createdRefs.toSet()
                }
            }
            cleanupUnreferencedAttachments(context, batch.conversationId, rollbackRefs)
        }
    }

    private fun releaseSavedPreparations(context: Context, conversationId: String, payload: JSONObject, refs: Set<String>) {
        val messageIds = listOf("uiMessages", "conversationMessages").flatMap { key ->
            val messages = payload.optJSONArray(key) ?: return@flatMap emptyList<String>()
            (0 until messages.length()).mapNotNull { messages.optJSONObject(it)?.optString("id") }
        }.toSet()
        synchronized(pinLock) {
            val keys = preparationBatches.filter { (key, batch) ->
                key.rootPath == context.filesDir.absolutePath && key.conversationId == conversationId &&
                    batch.committedMessageId in messageIds && refs.containsAll(batch.expectedRefs)
            }.keys.toList()
            keys.forEach { key -> preparationBatches.remove(key); attachmentPins.remove(key) }
        }
    }

    internal fun retainAttachmentPreparations(context: Context, conversationId: String, ownerId: String, messageIds: Set<String>) {
        synchronized(pinLock) {
            val rootPath = context.filesDir.absolutePath
            val key = PinKey(rootPath, conversationId, ownerId)
            if (messageIds.isEmpty()) retainedMessageIds.remove(key) else retainedMessageIds[key] = messageIds
            releaseUnusedPreparationsLocked(rootPath, conversationId)
        }
    }

    private fun releaseUnusedPreparationsLocked(rootPath: String, conversationId: String) {
        val hasSave = attachmentPins.keys.any {
            it.rootPath == rootPath && it.conversationId == conversationId && it.ownerId.startsWith("save:")
        }
        if (hasSave) return
        val retained = retainedMessageIds.filterKeys { it.rootPath == rootPath && it.conversationId == conversationId }.values.flatten().toSet()
        val unused = preparationBatches.filter { (key, batch) ->
            key.rootPath == rootPath && key.conversationId == conversationId && batch.finished &&
                batch.committedMessageId !in retained
        }.keys.toList()
        unused.forEach { preparationBatches.remove(it); attachmentPins.remove(it) }
    }

    // IO 清理；排队快照仍持有 save owner 时不得撤掉 pending-save pin。
    internal fun cleanupUnreferencedAttachments(context: Context, conversationId: String, onlyRefs: Set<String>? = null) {
        if (!isSafeConversationId(conversationId)) return
        synchronized(conversationMutationLock) {
            synchronized(pinLock) {
                releaseUnusedPreparationsLocked(context.filesDir.absolutePath, conversationId)
            }
            val diskRefs = readDiskAttachmentRefs(context, conversationId) ?: return
            val blobDir = File(File(File(context.filesDir, DirectoryName), BlobsDirectoryName), conversationId)
            if (onlyRefs == null) pruneStaleBlobs(context, conversationId, blobDir, diskRefs)
            else onlyRefs.forEach { ref ->
                synchronized(pinLock) {
                    if (ref !in diskRefs && !isRefPinned(context.filesDir.absolutePath, conversationId, ref)) {
                        val file = File(blobDir, ref)
                        if (file.exists() && !file.delete()) MoteLog.w(Component, "附件准备回滚清理失败，文件仍占用存储。")
                    }
                }
            }
        }
    }

    private fun readDiskAttachmentRefs(context: Context, conversationId: String): Set<String>? {
        val file = File(File(File(context.filesDir, DirectoryName), ConversationsDirectoryName), "$conversationId.json")
        return try {
            val attributes = try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                val corruptedDir = File(file.parentFile, CorruptedDirectoryName)
                val corruptedFiles = if (corruptedDir.exists()) {
                    corruptedDir.listFiles() ?: throw IOException("无法读取已隔离历史目录。")
                } else emptyArray()
                if (corruptedFiles.any { it.name.startsWith("$conversationId.") }) {
                    MoteLog.w(Component, "历史记录已隔离，保留附件文件与存储占用。")
                    return null
                }
                return emptySet()
            }
            check(attributes.isRegularFile && !attributes.isSymbolicLink)
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val refs = mutableSetOf<String>()
            listOf("uiMessages", "conversationMessages").forEach { key ->
                val messages = root.getJSONArray(key)
                for (index in 0 until messages.length()) {
                    val attachments = messages.getJSONObject(index).optJSONArray("attachments") ?: continue
                    for (attachmentIndex in 0 until attachments.length()) {
                        val attachment = attachments.getJSONObject(attachmentIndex)
                        val ref = attachment.optString("base64Ref").takeIf { it.isNotBlank() }
                        if (ref != null) {
                            check(isSafeBlobFileName(ref))
                            refs += ref
                        } else if (attachment.optString("base64Data").isNotBlank()) {
                            val id = attachment.optString("id")
                            if (SafeConversationIdPattern.matches(id)) refs += "$id.$BlobFileExtension"
                        }
                    }
                }
            }
            refs
        } catch (error: Exception) {
            MoteLog.w(Component, "无法可靠读取附件磁盘引用，保留文件；存储占用未释放。", error)
            null
        }
    }

    internal fun readAttachmentBase64(context: Context, conversationId: String, base64Ref: String): String {
        try {
            check(isSafeConversationId(conversationId) && isSafeBlobFileName(base64Ref))
            val file = File(File(File(File(context.filesDir, DirectoryName), BlobsDirectoryName), conversationId), base64Ref)
            check(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            return file.readText(Charsets.UTF_8).also { check(it.isNotBlank()) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            throw IllegalStateException("附件内容缺失或不可读，请重新选择图片。", error)
        }
    }

    internal fun materializeAttachmentsForRequest(context: Context, conversationId: String, messages: List<ChatMessage>): List<ChatMessage> {
        val cache = mutableMapOf<String, String>()
        return messages.map { message ->
            if (message.attachments.none { it.type == ChatAttachmentType.Image }) return@map message
            message.copy(attachments = message.attachments.map { attachment ->
                if (attachment.type != ChatAttachmentType.Image || !attachment.base64Data.isNullOrBlank()) attachment
                else {
                    val ref = attachment.base64Ref ?: throw IllegalStateException("附件内容缺失或不可读，请重新选择图片。")
                    attachment.copy(base64Data = cache.getOrPut(ref) { readAttachmentBase64(context, conversationId, ref) })
                }
            })
        }
    }

    data class HistoryStorageUsage(val totalBytes: Long, val attachmentBytes: Long, val fileCount: Int, val complete: Boolean)

    fun getStorageUsage(context: Context): HistoryStorageUsage = scanStorage(File(context.filesDir, DirectoryName))

    private fun scanStorage(root: File, attachmentsOnly: Boolean = false): HistoryStorageUsage {
        var totalBytes = 0L
        var attachmentBytes = 0L
        var fileCount = 0
        var complete = true
        fun visit(file: File, inBlobs: Boolean) {
            val attributes = try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                if (file != root) complete = false
                return
            } catch (_: Exception) { complete = false; return }
            if (file == root && !attributes.isDirectory) {
                complete = false
                return
            }
            when {
                attributes.isSymbolicLink -> complete = false
                attributes.isDirectory -> {
                    val children = try { file.listFiles() } catch (_: Exception) { null }
                    if (children == null) complete = false
                    else children.forEach { visit(it, inBlobs || (file == root && it.name == BlobsDirectoryName)) }
                }
                attributes.isRegularFile -> {
                    totalBytes += attributes.size()
                    if (inBlobs) attachmentBytes += attributes.size()
                    fileCount++
                }
                else -> complete = false
            }
        }
        visit(root, attachmentsOnly)
        return HistoryStorageUsage(totalBytes, attachmentBytes, fileCount, complete)
    }
    @Volatile
    private var legacyMigrationChecked: Boolean = false
    @Volatile
    private var cachedSummaryEntries: List<SummaryIndexEntry>? = null
    @Volatile
    private var orphanSweepDone: Boolean = false

    fun newConversationId(): String = UUID.randomUUID().toString()

    fun saveConversation(
        context: Context,
        settings: ApiSettings,
        conversationId: String,
        title: String,
        uiMessages: List<ChatMessage>,
        conversationMessages: List<ChatMessage>,
        contextSummaries: List<ContextSummary> = emptyList()
    ): File {
        synchronized(conversationMutationLock) {
            val conversationsDir = ensureConversationsDir(context)
            val safeConversationId = conversationId.ifBlank { newConversationId() }
            require(isSafeConversationId(safeConversationId)) { "对话 ID 不合法。" }
            val historyFile = conversationFile(conversationsDir, safeConversationId)
            // 优先走内存摘要缓存取 createdAt/title，避免保存前整文件回读（含全部附件 base64）。
            val metadata = conversationMetadata(safeConversationId, historyFile)
            val now = System.currentTimeMillis()
            val createdAt = metadata?.createdAt?.takeIf { it > 0L } ?: now
            val fallbackTitle = buildFallbackTitle(uiMessages)
            val existingTitle = metadata?.title.orEmpty()
            val incomingTitle = title.trim()
            val savedTitle = when {
                incomingTitle.isBlank() -> fallbackTitle
                existingTitle.isNotBlank() && existingTitle != fallbackTitle && incomingTitle == fallbackTitle -> existingTitle
                else -> incomingTitle
            }.ifBlank { fallbackTitle }

            val payload = JSONObject().apply {
                put("schemaVersion", SchemaVersion)
                put("id", safeConversationId)
                put("title", savedTitle)
                put("createdAt", createdAt)
                put("updatedAt", now)
                put("baseUrl", settings.resolvedChatModel()?.baseUrl.orEmpty())
                put("model", settings.resolvedChatModel()?.model.orEmpty())
                put("uiMessageCount", uiMessages.size)
                put("conversationMessageCount", conversationMessages.size)
                put("contextSummaryCount", contextSummaries.size)
                put("uiMessages", serializeMessages(uiMessages))
                put("conversationMessages", serializeMessages(conversationMessages))
                put("contextSummaries", serializeContextSummaries(contextSummaries))
            }
            writeConversationPayload(context, safeConversationId, historyFile, payload)
            val summaryEntry = readSummaryFileAttributes(historyFile)?.let { attributes ->
                parseSummaryIndexEntry(historyFile, payload, attributes)
            }
            if (loadCurrentConversationId(context) == safeConversationId) {
                saveCurrentConversationId(
                    context = context,
                    conversationId = safeConversationId,
                    allowMissingConversation = false
                )
            }
            summaryEntry?.let { upsertCachedSummary(context, it) }
            MoteLog.i(
                Component,
                MoteLog.event(
                    "已保存对话",
                    "conversationId" to MoteLog.shortId(safeConversationId),
                    "uiMessages" to uiMessages.size,
                    "conversationMessages" to conversationMessages.size,
                    "contextSummaries" to contextSummaries.size,
                    "titleLength" to savedTitle.length
                )
            )
            return historyFile
        }
    }

    fun loadCurrentConversation(context: Context): SavedConversationState {
        migrateLegacyConversationIfNeeded(context)

        val currentId = loadCurrentConversationId(context)
        if (currentId.isNotBlank() && isSafeConversationId(currentId)) {
            val current = loadConversation(context, currentId)
            if (current != null) {
                MoteLog.i(
                    Component,
                    MoteLog.event("已加载当前对话", "conversationId" to MoteLog.shortId(currentId))
                )
                return current
            }
            val currentFile = conversationFile(ensureConversationsDir(context), currentId)
            if (!currentFile.exists() && isMissingCurrentConversationAllowed(context)) {
                MoteLog.i(
                    Component,
                    MoteLog.event("当前对话文件允许缺失，返回空状态", "conversationId" to MoteLog.shortId(currentId))
                )
                return emptyConversationState(conversationId = currentId)
            }
            MoteLog.w(
                Component,
                MoteLog.event("当前对话缺失，清空索引", "conversationId" to MoteLog.shortId(currentId))
            )
            saveCurrentConversationId(context, "")
        } else if (currentId.isNotBlank()) {
            MoteLog.w(Component, "当前对话 ID 不合法，已清空索引。")
            saveCurrentConversationId(context, "")
        }

        val latest = listConversations(context).firstOrNull()
        if (latest != null) {
            saveCurrentConversationId(context, latest.id)
            MoteLog.i(
                Component,
                MoteLog.event("回退加载最近对话", "conversationId" to MoteLog.shortId(latest.id))
            )
            return loadConversation(context, latest.id) ?: emptyConversationState()
        }

        MoteLog.i(Component, "未找到历史对话，创建空对话状态。")
        return emptyConversationState(conversationId = newConversationId())
    }

    fun loadLatestConversation(context: Context): SavedConversationState {
        return loadCurrentConversation(context)
    }

    fun loadConversation(context: Context, conversationId: String): SavedConversationState? {
        migrateLegacyConversationIfNeeded(context)
        if (!isSafeConversationId(conversationId)) {
            return null
        }

        val file = conversationFile(ensureConversationsDir(context), conversationId)
        if (!file.exists() || !file.isFile) {
            return null
        }

        val root = readJsonObjectOrNull(file) ?: return null
        // v3 保留引用；图片内容仅在缩略图或实际请求的 IO 边界加载。
        return deserializeConversation(file, root).also { state ->
            MoteLog.d(
                Component,
                MoteLog.event(
                    "已反序列化对话",
                    "conversationId" to MoteLog.shortId(conversationId),
                    "uiMessages" to state.uiMessages.size,
                    "conversationMessages" to state.conversationMessages.size,
                    "contextSummaries" to state.contextSummaries.size
                )
            )
        }
    }

    fun listConversations(context: Context): List<ConversationSummary> {
        migrateLegacyConversationIfNeeded(context)

        val entries = synchronized(summaryCacheLock) {
            cachedSummaryEntries?.also { cached ->
                MoteLog.d(Component, MoteLog.event("对话列表缓存命中", "count" to cached.size))
            } ?: run {
                // 冷启动三级读取：内存缓存 → 摘要索引（须与目录一致）→ 全量扫描重建。
                val fromIndex = loadSummaryIndexOrNull(context)
                    ?.takeIf { summaryIndexMatchesDirectory(context, it) }
                val resolved = if (fromIndex != null) {
                    MoteLog.i(Component, MoteLog.event("摘要索引命中", "count" to fromIndex.size))
                    fromIndex
                } else {
                    scanConversationSummaries(context).also { rebuilt ->
                        writeSummaryIndex(context, rebuilt)
                    }
                }
                cachedSummaryEntries = resolved
                resolved
            }
        }
        sweepOrphanBlobDirsOnce(context)
        return entries.map { it.summary }
    }

    private fun scanConversationSummaries(context: Context): List<SummaryIndexEntry> {
        val conversationsDir = ensureConversationsDir(context)
        val files = conversationsDir.listFiles { file ->
            file.extension.equals("json", ignoreCase = true) &&
                    isSafeConversationId(file.nameWithoutExtension)
        }.orEmpty()
        return files.mapNotNull { file ->
            // 指纹必须先于正文读取；扫描中变更时，下次一致性校验会拒绝这份索引。
            val attributes = readSummaryFileAttributes(file) ?: return@mapNotNull null
            val root = readJsonObjectOrNull(file) ?: return@mapNotNull null
            parseSummaryIndexEntry(file, root, attributes)
        }.sortedByDescending { it.summary.updatedAt }.also { summaries ->
            MoteLog.i(
                Component,
                MoteLog.event("已扫描对话列表", "files" to files.size, "summaries" to summaries.size)
            )
        }
    }

    private fun upsertCachedSummary(context: Context, entry: SummaryIndexEntry) {
        synchronized(summaryCacheLock) {
            val entries = cachedSummaryEntries ?: return
            val updated = (entries.filterNot { it.summary.id == entry.summary.id } + entry)
                .sortedByDescending { it.summary.updatedAt }
            cachedSummaryEntries = updated
            writeSummaryIndex(context, updated)
        }
    }

    private fun removeCachedSummary(context: Context, conversationId: String) {
        synchronized(summaryCacheLock) {
            val entries = cachedSummaryEntries ?: return
            val updated = entries.filterNot { it.summary.id == conversationId }
            cachedSummaryEntries = updated
            writeSummaryIndex(context, updated)
        }
    }

    private fun resetCaches() {
        synchronized(summaryCacheLock) {
            legacyMigrationChecked = false
            cachedSummaryEntries = null
        }
    }

    fun saveCurrentConversationId(
        context: Context,
        conversationId: String,
        allowMissingConversation: Boolean = false
    ) {
        val historyDir = ensureHistoryDir(context)
        val indexFile = File(historyDir, IndexFileName)
        val safeConversationId = conversationId.takeIf { it.isBlank() || isSafeConversationId(it) }.orEmpty()
        writeJsonAtomically(
            indexFile,
            JSONObject().apply {
                put("currentConversationId", safeConversationId)
                put("allowMissingConversation", allowMissingConversation)
            }
        )
        MoteLog.d(
            Component,
            MoteLog.event(
                "已保存当前对话索引",
                "conversationId" to MoteLog.shortId(safeConversationId),
                "allowMissingConversation" to allowMissingConversation
            )
        )
    }

    data class ConversationDeletionResult(val replacementId: String?, val cleanupFailed: Boolean)

    fun deleteConversation(context: Context, conversationId: String): ConversationDeletionResult {
        synchronized(conversationMutationLock) {
            require(isSafeConversationId(conversationId)) { "对话 ID 不合法。" }
            val conversationsDir = ensureConversationsDir(context)
            val file = conversationFile(conversationsDir, conversationId)
            val blobDir = conversationBlobDir(context, conversationId)

            // unlink 成功或确认文件不存在即提交；此后的清理失败不能再报告为未删除。
            Files.deleteIfExists(file.toPath())
            synchronized(pinLock) {
                val rootPath = context.filesDir.absolutePath
                attachmentPins.keys.removeAll { it.rootPath == rootPath && it.conversationId == conversationId }
                preparationBatches.keys.removeAll { it.rootPath == rootPath && it.conversationId == conversationId }
                retainedMessageIds.keys.removeAll { it.rootPath == rootPath && it.conversationId == conversationId }
            }
            var cleanupFailed = false
            fun recordCleanupFailure(message: String, error: Exception) {
                if (error is CancellationException) throw error
                cleanupFailed = true
                MoteLog.w(Component, message, error)
            }

            try {
                removeCachedSummary(context, conversationId)
            } catch (error: Exception) {
                recordCleanupFailure("删除后的摘要缓存清理失败", error)
            }
            try {
                if (blobDir.exists() && !blobDir.deleteRecursively()) {
                    throw IOException("无法删除对话附件目录。")
                }
            } catch (error: Exception) {
                recordCleanupFailure("删除后的附件清理失败", error)
            }
            val replacementId = try {
                listConversations(context).firstOrNull { it.id != conversationId }?.id
            } catch (error: Exception) {
                recordCleanupFailure("删除后的替代对话读取失败", error)
                null
            }
            val currentId = try {
                loadCurrentConversationIdForDeletion(context)
            } catch (error: Exception) {
                recordCleanupFailure("删除后的当前对话索引读取失败", error)
                null
            }
            if (currentId != null && (currentId == conversationId || currentId.isBlank())) {
                try {
                    saveCurrentConversationId(context, replacementId.orEmpty())
                } catch (error: Exception) {
                    recordCleanupFailure("删除后的当前对话索引更新失败", error)
                }
            }
            sweepOrphanBlobDirs(context)
            MoteLog.i(
                Component,
                MoteLog.event(
                    "已删除对话",
                    "conversationId" to MoteLog.shortId(conversationId),
                    "replacementId" to MoteLog.shortId(replacementId),
                    "cleanupFailed" to cleanupFailed
                )
            )
            return ConversationDeletionResult(replacementId, cleanupFailed)
        }
    }

    fun updateConversationTitle(context: Context, conversationId: String, title: String): Boolean {
        synchronized(conversationMutationLock) {
            val normalizedTitle = title.trim().takeIf { it.isNotBlank() } ?: return false
            if (!isSafeConversationId(conversationId)) {
                return false
            }
            val conversationsDir = ensureConversationsDir(context)
            val file = conversationFile(conversationsDir, conversationId)
            val root = readJsonObjectOrNull(file) ?: return false
            root.put("title", normalizeTitle(normalizedTitle))
            writeJsonAtomically(file, root)
            readSummaryFileAttributes(file)?.let { attributes ->
                parseSummaryIndexEntry(file, root, attributes)
            }?.let { upsertCachedSummary(context, it) }
            MoteLog.i(
                Component,
                MoteLog.event(
                    "已更新对话标题",
                    "conversationId" to MoteLog.shortId(conversationId),
                    "titleLength" to normalizedTitle.length
                )
            )
            return true
        }
    }

    fun clearConversation(context: Context) {
        synchronized(conversationMutationLock) {
            val historyDir = File(context.filesDir, DirectoryName)
            if (!historyDir.exists()) {
                resetCaches()
                return
            }
            if (!historyDir.deleteRecursively()) {
                throw IllegalStateException("无法删除历史记录目录。")
            }
            resetCaches()
            MoteLog.i(Component, "已清空全部聊天历史。")
        }
    }

    private fun migrateLegacyConversationIfNeeded(context: Context): Unit = synchronized(conversationMutationLock) migration@ {
        if (legacyMigrationChecked) {
            return@migration
        }

        synchronized(summaryCacheLock) {
            if (legacyMigrationChecked) {
                return@migration
            }

            val historyDir = ensureHistoryDir(context)
            val conversationsDir = ensureConversationsDir(context)
            val migrationMarkerFile = File(historyDir, LegacyMigrationMarkerFileName)
            val hasConversationFiles = conversationsDir.listFiles { file ->
                file.isFile &&
                        file.extension.equals("json", ignoreCase = true) &&
                        isSafeConversationId(file.nameWithoutExtension)
            }?.isNotEmpty() == true
            if (hasConversationFiles || migrationMarkerFile.exists()) {
                legacyMigrationChecked = true
                MoteLog.d(
                    Component,
                    MoteLog.event(
                        "跳过旧历史迁移",
                        "hasConversationFiles" to hasConversationFiles,
                        "hasMarker" to migrationMarkerFile.exists()
                    )
                )
                return@migration
            }

            val legacyFile = File(historyDir, LegacyFileName)
            if (!legacyFile.exists() || !legacyFile.isFile) {
                legacyMigrationChecked = true
                MoteLog.d(Component, "未发现旧版历史文件，跳过迁移。")
                return@migration
            }

            val legacyRoot = readJsonObjectOrNull(legacyFile)
            if (legacyRoot == null) {
                legacyMigrationChecked = true
                MoteLog.w(Component, "旧版历史文件无法解析，跳过迁移。")
                return@migration
            }

            val legacyState = deserializeLegacyConversation(legacyRoot)
            if (legacyState.uiMessages.isEmpty() && legacyState.conversationMessages.isEmpty()) {
                writeJsonAtomically(
                    migrationMarkerFile,
                    JSONObject().apply {
                        put("migratedAt", System.currentTimeMillis())
                        put("empty", true)
                    }
                )
                legacyMigrationChecked = true
                cachedSummaryEntries = null
                MoteLog.i(Component, "旧版历史为空，已写入迁移标记。")
                return@migration
            }

            val conversationId = newConversationId()
            val legacyTitle = buildFallbackTitle(legacyState.uiMessages)
            val timestamp = legacyFile.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            val migratedRoot = JSONObject().apply {
                put("schemaVersion", SchemaVersion)
                put("id", conversationId)
                put("title", legacyTitle)
                put("createdAt", timestamp)
                put("updatedAt", timestamp)
                put("baseUrl", legacyRoot.optString("baseUrl"))
                put("model", legacyRoot.optString("model"))
                put("uiMessageCount", legacyState.uiMessages.size)
                put("conversationMessageCount", legacyState.conversationMessages.size)
                put("contextSummaryCount", legacyState.contextSummaries.size)
                put("uiMessages", serializeMessages(legacyState.uiMessages))
                put("conversationMessages", serializeMessages(legacyState.conversationMessages))
                put("contextSummaries", serializeContextSummaries(legacyState.contextSummaries))
            }
            writeConversationPayload(context, conversationId, conversationFile(conversationsDir, conversationId), migratedRoot)
            if (loadCurrentConversationId(context).isBlank()) {
                saveCurrentConversationId(context, conversationId)
            }
            writeJsonAtomically(
                migrationMarkerFile,
                JSONObject().apply {
                    put("migratedAt", System.currentTimeMillis())
                    put("conversationId", conversationId)
                }
            )
            legacyMigrationChecked = true
            cachedSummaryEntries = null
            MoteLog.i(
                Component,
                MoteLog.event(
                    "旧版历史迁移完成",
                    "conversationId" to MoteLog.shortId(conversationId),
                    "uiMessages" to legacyState.uiMessages.size,
                    "conversationMessages" to legacyState.conversationMessages.size,
                    "contextSummaries" to legacyState.contextSummaries.size
                )
            )
        }
    }

    private fun deserializeLegacyConversation(root: JSONObject): SavedConversationState {
        val uiMessageArray = root.optJSONArray("uiMessages")
        val conversationMessageArray = root.optJSONArray("conversationMessages")
        val contextSummaryArray = root.optJSONArray("contextSummaries")
        val legacyMessages = root.optJSONArray("messages")

        return when {
            uiMessageArray != null || conversationMessageArray != null -> {
                val conversationMessages = deserializeMessages(conversationMessageArray)
                val separated = separateEmbeddedContextSummaries(conversationMessages)
                SavedConversationState(
                    uiMessages = deserializeMessages(uiMessageArray),
                    conversationMessages = separated.messages,
                    contextSummaries = mergeContextSummaries(
                        deserializeContextSummaries(contextSummaryArray),
                        separated.summaries
                    )
                )
            }

            legacyMessages != null -> {
                val legacyList = deserializeMessages(legacyMessages)
                val separated = separateEmbeddedContextSummaries(
                    legacyList.filter { it.role != ChatRole.Tool }
                )
                SavedConversationState(
                    uiMessages = legacyList,
                    conversationMessages = separated.messages,
                    contextSummaries = separated.summaries
                )
            }

            else -> emptyConversationState()
        }
    }

    private fun deserializeConversation(file: File, root: JSONObject): SavedConversationState {
        val uiMessages = deserializeMessages(root.optJSONArray("uiMessages"))
        val rawConversationMessages = deserializeMessages(root.optJSONArray("conversationMessages"))
        val separated = separateEmbeddedContextSummaries(rawConversationMessages)
        val savedContextSummaries = deserializeContextSummaries(root.optJSONArray("contextSummaries"))
        val conversationId = file.nameWithoutExtension
        return SavedConversationState(
            uiMessages = uiMessages,
            conversationMessages = separated.messages,
            contextSummaries = mergeContextSummaries(savedContextSummaries, separated.summaries),
            conversationId = conversationId,
            title = root.optString("title").ifBlank { buildFallbackTitle(uiMessages) }
        )
    }

    private data class SeparatedContextSummaries(
        val messages: List<ChatMessage>,
        val summaries: List<ContextSummary>
    )

    private fun separateEmbeddedContextSummaries(messages: List<ChatMessage>): SeparatedContextSummaries {
        val summaries = messages
            .filter { it.isContextSummary }
            .filter { it.content.isNotBlank() && it.contextSummarySourceIds.isNotEmpty() }
            .map { message ->
                ContextSummary(
                    id = message.id,
                    content = message.content,
                    sourceMessageIds = message.contextSummarySourceIds.distinct()
                )
            }
        return SeparatedContextSummaries(
            messages = messages.filterNot { it.isContextSummary },
            summaries = summaries
        )
    }

    private fun mergeContextSummaries(
        first: List<ContextSummary>,
        second: List<ContextSummary>
    ): List<ContextSummary> {
        return (first + second)
            .distinctBy { it.id }
            .filter { it.content.isNotBlank() && it.sourceMessageIds.isNotEmpty() }
    }

    private fun parseConversationSummary(file: File, root: JSONObject, fileMtime: Long = file.lastModified()): ConversationSummary? {
        val conversationId = file.nameWithoutExtension
        if (!isSafeConversationId(conversationId)) {
            return null
        }
        val uiMessages = root.optJSONArray("uiMessages")
        val messageCount = root.optInt("uiMessageCount", uiMessages?.length() ?: 0)
        return ConversationSummary(
            id = conversationId,
            title = root.optString("title").ifBlank {
                buildFallbackTitle(deserializeMessages(uiMessages))
            },
            createdAt = root.optLong("createdAt", fileMtime).takeIf { it > 0L } ?: fileMtime,
            updatedAt = root.optLong("updatedAt", fileMtime).takeIf { it > 0L } ?: fileMtime,
            messageCount = messageCount
        )
    }

    private fun readSummaryFileAttributes(file: File): BasicFileAttributes? = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    }.getOrNull()?.takeIf { it.isRegularFile }

    private fun parseSummaryIndexEntry(file: File, root: JSONObject, attributes: BasicFileAttributes): SummaryIndexEntry? {
        val fileMtime = attributes.lastModifiedTime().toMillis()
        val summary = parseConversationSummary(file, root, fileMtime) ?: return null
        return SummaryIndexEntry(summary, fileMtime, attributes.size())
    }

    /** 取对话的 createdAt/title 元数据：优先内存摘要缓存（0 次读盘），未命中时回退单文件读取。 */
    private fun conversationMetadata(conversationId: String, file: File): ConversationSummary? {
        synchronized(summaryCacheLock) {
            cachedSummaryEntries?.firstOrNull { it.summary.id == conversationId }?.summary?.let { return it }
        }
        if (!file.exists()) {
            return null
        }
        val root = readJsonObjectOrNull(file) ?: return null
        return parseConversationSummary(file, root)
    }

    private fun summaryIndexFile(context: Context): File = File(ensureHistoryDir(context), SummaryIndexFileName)

    /** 索引是衍生数据；仅写 entry 自带指纹，不为旧摘要重新读取 stat。写失败只告警。 */
    private fun writeSummaryIndex(context: Context, entries: List<SummaryIndexEntry>) {
        runCatching {
            writeJsonAtomically(
                summaryIndexFile(context),
                JSONObject().apply {
                    put("schemaVersion", SummaryIndexSchemaVersion)
                    put("updatedAt", System.currentTimeMillis())
                    put(
                        "conversations",
                        JSONArray().apply {
                            entries.forEach { entry ->
                                val summary = entry.summary
                                put(
                                    JSONObject().apply {
                                        put("id", summary.id)
                                        put("title", summary.title)
                                        put("createdAt", summary.createdAt)
                                        put("updatedAt", summary.updatedAt)
                                        put("messageCount", summary.messageCount)
                                        put("fileMtime", entry.fileMtime)
                                        put("fileLength", entry.fileLength)
                                    }
                                )
                            }
                        }
                    )
                }
            )
        }.onFailure { error ->
            MoteLog.w(Component, MoteLog.event("写入摘要索引失败", "count" to entries.size), error)
        }
    }

    /** 索引损坏则删除，版本或字段无效则返回 null；调用方全量扫描重建。 */
    private fun loadSummaryIndexOrNull(context: Context): List<SummaryIndexEntry>? {
        val file = summaryIndexFile(context)
        if (!file.exists() || !file.isFile) {
            return null
        }
        val root = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrElse {
            MoteLog.w(Component, MoteLog.event("摘要索引损坏，已删除待重建", "file" to file.name))
            file.delete()
            return null
        }
        if (root.optInt("schemaVersion", 0) != SummaryIndexSchemaVersion) {
            return null
        }
        val array = root.optJSONArray("conversations") ?: return null
        val entries = buildList {
            val ids = mutableSetOf<String>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: return null
                val id = item.optString("id")
                if (!isSafeConversationId(id) || !ids.add(id)) {
                    return null
                }
                val fileMtime = summaryFingerprintLongOrNull(item.opt("fileMtime")) ?: return null
                val fileLength = summaryFingerprintLongOrNull(item.opt("fileLength")) ?: return null
                add(
                    SummaryIndexEntry(
                        summary = ConversationSummary(
                            id = id,
                            title = item.optString("title").ifBlank { DefaultConversationTitle },
                            createdAt = item.optLong("createdAt", 0L),
                            updatedAt = item.optLong("updatedAt", 0L),
                            messageCount = item.optInt("messageCount", 0)
                        ),
                        fileMtime = fileMtime,
                        fileLength = fileLength
                    )
                )
            }
        }
        return entries.sortedByDescending { it.summary.updatedAt }
    }

    private fun summaryFingerprintLongOrNull(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        else -> null
    }?.takeIf { it >= 0L }

    /** 校验 ID 与内容绑定的 mtime/length；同 ID 文件被更新也必须废弃旧摘要索引。 */
    private fun summaryIndexMatchesDirectory(context: Context, entries: List<SummaryIndexEntry>): Boolean {
        val files = ensureConversationsDir(context).listFiles { file ->
            file.extension.equals("json", ignoreCase = true) &&
                    isSafeConversationId(file.nameWithoutExtension)
        } ?: return false
        if (files.size != entries.size) return false
        val entriesById = entries.associateByTo(mutableMapOf()) { it.summary.id }
        return files.all { file ->
            val entry = entriesById.remove(file.nameWithoutExtension) ?: return@all false
            val attributes = readSummaryFileAttributes(file) ?: return@all false
            entry.fileMtime == attributes.lastModifiedTime().toMillis() &&
                    entry.fileLength == attributes.size()
        }
    }

    private fun blobsRootDir(context: Context): File = File(ensureHistoryDir(context), BlobsDirectoryName)

    private fun conversationBlobDir(context: Context, conversationId: String): File =
        File(blobsRootDir(context), conversationId)

    private fun isSafeBlobFileName(name: String): Boolean =
        name.endsWith(".$BlobFileExtension") &&
                SafeConversationIdPattern.matches(name.removeSuffix(".$BlobFileExtension"))

    /** 统一写出对话 JSON：先外置附件 blob，再原子写 JSON（引用必有实体），最后清理不再引用的 blob。 */
    private fun writeConversationPayload(context: Context, conversationId: String, file: File, payload: JSONObject) {
        val blobDir = conversationBlobDir(context, conversationId)
        val referenced = externalizeAttachmentBlobs(payload, blobDir)
        writeJsonAtomically(file, payload)
        releaseSavedPreparations(context, conversationId, payload, referenced)
        pruneStaleBlobs(context, conversationId, blobDir, referenced)
    }

    /**
     * 遍历 payload 的双份消息列表，把附件内联 base64 落盘为 blob 并替换为 base64Ref；返回本次引用的 blob 文件名。
     * blob 内容按附件 id 不可变，目标文件已存在则跳过写入；附件 id 不合法时保持内联，不中断保存。
     */
    private fun externalizeAttachmentBlobs(payload: JSONObject, blobDir: File): Set<String> {
        val referenced = mutableSetOf<String>()
        listOf("uiMessages", "conversationMessages").forEach { key ->
            val messages = payload.optJSONArray(key) ?: return@forEach
            for (messageIndex in 0 until messages.length()) {
                val attachments = messages.optJSONObject(messageIndex)?.optJSONArray("attachments") ?: continue
                for (attachmentIndex in 0 until attachments.length()) {
                    val attachment = attachments.optJSONObject(attachmentIndex) ?: continue
                    val existingRef = attachment.optString("base64Ref").takeIf { it.isNotBlank() }
                    if (existingRef != null) {
                        if (isSafeBlobFileName(existingRef)) {
                            referenced += existingRef
                            attachment.remove("base64Data")
                        }
                        continue
                    }
                    val base64 = attachment.optString("base64Data").takeIf { it.isNotEmpty() } ?: continue
                    val attachmentId = attachment.optString("id")
                    if (!SafeConversationIdPattern.matches(attachmentId)) {
                        continue
                    }
                    val blobName = "$attachmentId.$BlobFileExtension"
                    val blobFile = File(blobDir, blobName)
                    if (!blobFile.exists()) {
                        writeBlobAtomically(blobFile, base64)
                    }
                    attachment.remove("base64Data")
                    attachment.put("base64Ref", blobName)
                    referenced += blobName
                }
            }
        }
        return referenced
    }


    /** 删除 blobDir 下不再被引用的 blob 与残留临时文件；全部清空后顺带删除空目录。 */
    private fun pruneStaleBlobs(context: Context, conversationId: String, blobDir: File, referenced: Set<String>) {
        val files = blobDir.listFiles { file -> file.isFile } ?: run {
            if (blobDir.exists()) MoteLog.w(Component, "附件目录无法读取，未完成清理，文件仍占用存储。")
            return
        }
        files.forEach { file ->
            synchronized(pinLock) {
                val pinned = isRefPinned(context.filesDir.absolutePath, conversationId, file.name)
                val stale = file.name.endsWith(".tmp") ||
                        (file.name.endsWith(".$BlobFileExtension") && file.name !in referenced && !pinned)
                if (stale && !file.delete()) {
                    MoteLog.w(Component, "无法删除滞留附件文件。")
                }
            }
        }
        synchronized(pinLock) {
            if (referenced.isEmpty() && !hasPins(context.filesDir.absolutePath, conversationId)) blobDir.delete()
        }
    }

    private fun sweepOrphanBlobDirsOnce(context: Context) {
        if (orphanSweepDone) {
            return
        }
        synchronized(summaryCacheLock) {
            if (orphanSweepDone) {
                return
            }
            orphanSweepDone = true
        }
        sweepOrphanBlobDirs(context)
    }

    /** 清理没有对应对话文件的 blob 子目录。被隔离对话（corrupted/{id}.{时间戳}.corrupt.json）的 blob 保留以便恢复。 */
    private fun sweepOrphanBlobDirs(context: Context): Unit = synchronized(conversationMutationLock) sweep@ {
        runCatching {
            val blobDirs = blobsRootDir(context).listFiles { file -> file.isDirectory }.orEmpty()
            if (blobDirs.isEmpty()) {
                return@sweep
            }
            val conversationsDir = ensureConversationsDir(context)
            val liveIds = conversationsDir.listFiles { file ->
                file.isFile &&
                        file.extension.equals("json", ignoreCase = true) &&
                        isSafeConversationId(file.nameWithoutExtension)
            }?.map { it.nameWithoutExtension }?.toSet().orEmpty()
            val corruptedIds = File(conversationsDir, CorruptedDirectoryName)
                .listFiles()?.map { it.name.substringBefore('.') }?.toSet().orEmpty()
            var removed = 0
            blobDirs.forEach { dir ->
                if (dir.name !in liveIds && dir.name !in corruptedIds) {
                    val files = dir.listFiles() ?: return@forEach
                    files.forEach { file ->
                        synchronized(pinLock) {
                            if (!hasPins(context.filesDir.absolutePath, dir.name) && !file.delete()) {
                                MoteLog.w(Component, "无法删除孤儿附件文件。")
                            }
                        }
                    }
                    synchronized(pinLock) {
                        if (!hasPins(context.filesDir.absolutePath, dir.name) && dir.delete()) removed++
                    }
                }
            }
            if (removed > 0) {
                MoteLog.i(Component, MoteLog.event("已清理孤儿附件目录", "count" to removed))
            }
        }.onFailure { error ->
            MoteLog.w(Component, "清理孤儿附件目录失败。", error)
        }
        Unit
    }

    private fun loadCurrentConversationIdForDeletion(context: Context): String {
        val indexFile = File(ensureHistoryDir(context), IndexFileName)
        val attributes = try {
            Files.readAttributes(indexFile.toPath(), BasicFileAttributes::class.java)
        } catch (_: NoSuchFileException) {
            return ""
        }
        if (!attributes.isRegularFile) throw IOException("当前对话索引不是普通文件。")
        val currentId = JSONObject(indexFile.readText(Charsets.UTF_8))
            .optString("currentConversationId").orEmpty()
        if (currentId.isBlank()) return ""
        check(isSafeConversationId(currentId)) { "当前对话 ID 不合法。" }
        return currentId
    }

    private fun loadCurrentConversationId(context: Context): String {
        val indexFile = File(ensureHistoryDir(context), IndexFileName)
        return readJsonObjectOrNull(indexFile)
            ?.optString("currentConversationId")
            .orEmpty()
            .takeIf { it.isBlank() || isSafeConversationId(it) }
            .orEmpty()
    }

    private fun isMissingCurrentConversationAllowed(context: Context): Boolean {
        val indexFile = File(ensureHistoryDir(context), IndexFileName)
        return readJsonObjectOrNull(indexFile)?.optBoolean("allowMissingConversation", false) == true
    }

    private fun ensureHistoryDir(context: Context): File {
        val historyDir = File(context.filesDir, DirectoryName)
        if (!historyDir.exists() && !historyDir.mkdirs()) {
            throw IllegalStateException("无法创建聊天记录目录。")
        }
        return historyDir
    }

    private fun ensureConversationsDir(context: Context): File {
        val conversationsDir = File(ensureHistoryDir(context), ConversationsDirectoryName)
        if (!conversationsDir.exists() && !conversationsDir.mkdirs()) {
            throw IllegalStateException("无法创建对话记录目录。")
        }
        return conversationsDir
    }

    private fun conversationFile(conversationsDir: File, conversationId: String): File {
        val safeConversationId = conversationId.ifBlank { newConversationId() }
        require(isSafeConversationId(safeConversationId)) { "对话 ID 不合法。" }
        val canonicalDir = conversationsDir.canonicalFile
        if (!canonicalDir.exists() && !canonicalDir.mkdirs()) {
            throw IllegalStateException("无法创建对话记录目录。")
        }
        val file = File(canonicalDir, "$safeConversationId.json").canonicalFile
        require(file.path.startsWith(canonicalDir.path + File.separator)) { "对话记录路径不合法。" }
        return file
    }

    private fun isSafeConversationId(conversationId: String): Boolean {
        return SafeConversationIdPattern.matches(conversationId)
    }

    private fun readJsonObjectOrNull(file: File): JSONObject? {
        if (!file.exists() || !file.isFile) {
            return null
        }
        // 读取失败多为瞬时 IO 错误，不当作损坏隔离，留待下次重试；只有内容确实解析失败才隔离。
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse { error ->
            MoteLog.e(Component, MoteLog.event("读取历史记录失败", "file" to file.name), error)
            return null
        }
        return runCatching { JSONObject(text) }.getOrElse { error ->
            MoteLog.e(Component, MoteLog.event("解析历史记录失败", "file" to file.name), error)
            quarantineCorruptedJsonFile(file)
            null
        }
    }

    private fun quarantineCorruptedJsonFile(file: File) {
        if (!file.extension.equals("json", ignoreCase = true)) {
            return
        }

        val parent = file.parentFile ?: return
        if (parent.name == CorruptedDirectoryName || file.name == IndexFileName || file.name == SummaryIndexFileName) {
            return
        }

        val corruptedDir = File(parent, CorruptedDirectoryName)
        if (!corruptedDir.exists() && !corruptedDir.mkdirs()) {
            MoteLog.e(Component, "无法创建损坏历史隔离目录。")
            return
        }

        val quarantinedFile = File(
            corruptedDir,
            "${file.nameWithoutExtension}.${System.currentTimeMillis()}.corrupt.json"
        )
        if (!file.renameTo(quarantinedFile)) {
            MoteLog.e(Component, MoteLog.event("无法隔离损坏历史记录", "file" to file.name))
        } else {
            MoteLog.w(Component, MoteLog.event("已隔离损坏历史记录", "file" to file.name))
        }
    }

    private fun writeJsonAtomically(file: File, payload: JSONObject) {
        val parent = file.parentFile ?: throw IllegalStateException("记录文件路径无效。")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("无法创建记录目录。")
        }

        val tempFile = File(parent, "${file.name}.${UUID.randomUUID()}.tmp")
        runCatching {
            FileOutputStream(tempFile).use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                    output.fd.sync()
                }
            }
            moveReplacing(tempFile, file)
        }.onFailure { error ->
            tempFile.delete()
            throw error
        }
    }

    /** 写附件 blob 文本：temp + fsync + 原子替换，与 [writeJsonAtomically] 同规格。 */
    private fun writeBlobAtomically(file: File, text: String, onMoved: (() -> Unit)? = null) {
        val parent = file.parentFile ?: throw IllegalStateException("附件文件路径无效。")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IllegalStateException("无法创建附件目录。")
        }

        val tempFile = File(parent, "${file.name}.${UUID.randomUUID()}.tmp")
        runCatching {
            FileOutputStream(tempFile).use { output ->
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    writer.write(text)
                    writer.flush()
                    output.fd.sync()
                }
            }
            moveReplacing(tempFile, file, onMoved)
        }.onFailure { error ->
            tempFile.delete()
            throw error
        }
    }

    private fun moveReplacing(source: File, target: File, onMoved: (() -> Unit)? = null) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // 降级路径等价 copy+delete，非原子；记录警告便于定位半截文件，损坏由 quarantine 兜底。
            MoteLog.w(Component, MoteLog.event("文件系统不支持原子移动，降级为普通替换", "file" to target.name))
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        onMoved?.invoke()
        fsyncDirectoryQuietly(target.parentFile)
    }

    /**
     * rename 后 fsync 父目录，掉电时保证新目录项已持久化。
     * 不支持以只读方式打开目录的平台（如 Windows 测试环境）静默跳过，属尽力而为。
     */
    private fun fsyncDirectoryQuietly(dir: File?) {
        dir ?: return
        runCatching {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        }
    }

    private fun buildFallbackTitle(messages: List<ChatMessage>): String {
        val firstUser = messages.firstOrNull { it.role == ChatRole.User }
        val firstUserMessage = firstUser
            ?.content
            .orEmpty()
            .ifBlank { buildAttachmentTitleSeed(firstUser?.attachments.orEmpty()) }
        return normalizeTitle(firstUserMessage).ifBlank { DefaultConversationTitle }
    }

    private fun buildAttachmentTitleSeed(attachments: List<ChatAttachment>): String {
        return attachments.joinToString(separator = " ") { attachment ->
            val label = when (attachment.type) {
                ChatAttachmentType.Image -> "图片"
                ChatAttachmentType.File -> "文件"
            }
            "$label：${attachment.displayName.ifBlank { attachment.path }}"
        }
    }

    private fun normalizeTitle(value: String): String {
        val compact = value.trim().replace(Regex("\\s+"), " ")
        return if (compact.length > 24) {
            compact.take(24).trimEnd() + "..."
        } else {
            compact
        }
    }

    private fun emptyConversationState(conversationId: String = ""): SavedConversationState {
        return SavedConversationState(
            uiMessages = emptyList(),
            conversationMessages = emptyList(),
            conversationId = conversationId,
            title = DefaultConversationTitle
        )
    }

    internal fun serializeMessages(messages: List<ChatMessage>): JSONArray {
        return JSONArray().apply {
            messages.forEach { message ->
                put(
                    JSONObject().apply {
                        put("id", message.id)
                        put("role", message.role.apiValue)
                        put("content", message.content)
                        message.toolCallId?.let { put("toolCallId", it) }
                        message.toolName?.let { put("toolName", it) }
                        message.toolArguments?.let { put("toolArguments", it) }
                        put("excludeFromConversation", message.excludeFromConversation)
                        put("isContextSummary", message.isContextSummary)
                        if (message.contextSummarySourceIds.isNotEmpty()) {
                            put(
                                "contextSummarySourceIds",
                                JSONArray().apply {
                                    message.contextSummarySourceIds.forEach { sourceId -> put(sourceId) }
                                }
                            )
                        }
                        if (message.attachments.isNotEmpty()) {
                            put("attachments", serializeAttachments(message.attachments))
                        }
                        if (message.toolCalls.isNotEmpty()) {
                            put(
                                "toolCalls",
                                JSONArray().apply {
                                    message.toolCalls.forEach { toolCall ->
                                        put(
                                            JSONObject().apply {
                                                put("id", toolCall.id)
                                                put("name", toolCall.name)
                                                put("arguments", toolCall.arguments)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                        if (message.assistantParts.isNotEmpty()) {
                            put("assistantParts", serializeAssistantParts(message.assistantParts))
                        }
                        message.errorNotice?.let { put("errorNotice", it) }
                        message.uiNoticeKind?.let { put("uiNoticeKind", it) }
                        message.uiNoticeRefId?.let { put("uiNoticeRefId", it) }
                    }
                )
            }
        }
    }

    internal fun serializeContextSummaries(summaries: List<ContextSummary>): JSONArray {
        return JSONArray().apply {
            summaries.forEach { summary ->
                if (summary.content.isBlank() || summary.sourceMessageIds.isEmpty()) {
                    return@forEach
                }
                put(
                    JSONObject().apply {
                        put("id", summary.id)
                        put("content", summary.content)
                        put(
                            "sourceMessageIds",
                            JSONArray().apply {
                                summary.sourceMessageIds.distinct().forEach { sourceId ->
                                    if (sourceId.isNotBlank()) {
                                        put(sourceId)
                                    }
                                }
                            }
                        )
                        put("createdAt", summary.createdAt)
                        put("updatedAt", summary.updatedAt)
                    }
                )
            }
        }
    }

    private fun serializeAttachments(attachments: List<ChatAttachment>): JSONArray {
        return JSONArray().apply {
            attachments.forEach { attachment ->
                put(
                    JSONObject().apply {
                        put("id", attachment.id)
                        put("type", attachment.type.storageValue)
                        put("displayName", attachment.displayName)
                        attachment.mimeType?.let { put("mimeType", it) }
                        put("path", attachment.path)
                        put("directReadable", attachment.directReadable)
                        if (attachment.textContent != null) {
                            put("textContent", attachment.textContent)
                        }
                        attachment.base64Data?.let { put("base64Data", it) }
                        attachment.base64Ref?.let { put("base64Ref", it) }
                        put("truncated", attachment.truncated)
                    }
                )
            }
        }
    }

    private fun serializeAssistantParts(parts: List<AssistantPart>): JSONArray {
        return JSONArray().apply {
            parts.forEach { part ->
                put(
                    JSONObject().apply {
                        put("id", part.id)
                        when (part) {
                            is AssistantMarkdownPart -> {
                                put("type", "markdown")
                                put("text", part.text)
                            }

                            is AssistantThinkingPart -> {
                                put("type", "thinking")
                                put("text", part.text)
                            }

                            is AssistantToolPart -> {
                                put("type", "tool")
                                put("toolName", part.toolName)
                                put("toolArguments", part.toolArguments)
                                put("result", part.result)
                            }
                        }
                    }
                )
            }
        }
    }

    internal fun deserializeMessages(messageArray: JSONArray?): List<ChatMessage> {
        if (messageArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until messageArray.length()) {
                val item = messageArray.optJSONObject(index) ?: continue
                val role = item.optString("role").toChatRoleOrNull() ?: continue
                add(
                    ChatMessage(
                        id = item.optString("id", UUID.randomUUID().toString()),
                        role = role,
                        content = item.optString("content"),
                        toolCallId = item.optString("toolCallId").takeIf { it.isNotBlank() },
                        toolName = item.optString("toolName").takeIf { it.isNotBlank() },
                        toolArguments = item.optString("toolArguments").takeIf { it.isNotBlank() },
                        toolCalls = deserializeToolCalls(item.optJSONArray("toolCalls")),
                        assistantParts = deserializeAssistantParts(item.optJSONArray("assistantParts")),
                        attachments = deserializeAttachments(item.optJSONArray("attachments")),
                        excludeFromConversation = item.optBoolean("excludeFromConversation", false),
                        isContextSummary = item.optBoolean("isContextSummary", false),
                        contextSummarySourceIds = deserializeStringList(item.optJSONArray("contextSummarySourceIds")),
                        errorNotice = item.optString("errorNotice").takeIf { it.isNotBlank() },
                        uiNoticeKind = item.optString("uiNoticeKind").takeIf { it.isNotBlank() },
                        uiNoticeRefId = item.optString("uiNoticeRefId").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    internal fun deserializeContextSummaries(summaryArray: JSONArray?): List<ContextSummary> {
        if (summaryArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until summaryArray.length()) {
                val item = summaryArray.optJSONObject(index) ?: continue
                val content = item.optString("content").takeIf { it.isNotBlank() } ?: continue
                val sourceIds = deserializeStringList(item.optJSONArray("sourceMessageIds"))
                    .distinct()
                if (sourceIds.isEmpty()) {
                    continue
                }
                val createdAt = item.optLong("createdAt", 0L).takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                add(
                    ContextSummary(
                        id = item.optString("id", UUID.randomUUID().toString()),
                        content = content,
                        sourceMessageIds = sourceIds,
                        createdAt = createdAt,
                        updatedAt = item.optLong("updatedAt", createdAt).takeIf { it > 0L }
                            ?: createdAt
                    )
                )
            }
        }
    }

    private fun deserializeStringList(array: JSONArray?): List<String> {
        if (array == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    private fun deserializeAttachments(attachmentsArray: JSONArray?): List<ChatAttachment> {
        if (attachmentsArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until attachmentsArray.length()) {
                val item = attachmentsArray.optJSONObject(index) ?: continue
                val type = item.optString("type").toChatAttachmentTypeOrNull() ?: continue
                val path = item.optString("path")
                val displayName = item.optString("displayName").ifBlank { path }
                if (displayName.isBlank() && path.isBlank()) {
                    continue
                }
                add(
                    ChatAttachment(
                        id = item.optString("id", UUID.randomUUID().toString()),
                        type = type,
                        displayName = displayName,
                        mimeType = item.optString("mimeType").takeIf { it.isNotBlank() },
                        path = path,
                        directReadable = item.optBoolean("directReadable", false),
                        textContent = if (item.has("textContent") && !item.isNull("textContent")) {
                            item.optString("textContent")
                        } else {
                            null
                        },
                        base64Data = item.optString("base64Data").takeIf { it.isNotBlank() },
                        truncated = item.optBoolean("truncated", false),
                        base64Ref = item.optString("base64Ref").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    private fun String.toChatAttachmentTypeOrNull(): ChatAttachmentType? {
        return ChatAttachmentType.values().firstOrNull { it.storageValue == this }
    }

    private fun deserializeAssistantParts(partsArray: JSONArray?): List<AssistantPart> {
        if (partsArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until partsArray.length()) {
                val item = partsArray.optJSONObject(index) ?: continue
                val id = item.optString("id", UUID.randomUUID().toString())
                when (item.optString("type")) {
                    "markdown" -> add(
                        AssistantMarkdownPart(
                            id = id,
                            text = item.optString("text")
                        )
                    )

                    "thinking" -> add(
                        AssistantThinkingPart(
                            id = id,
                            text = item.optString("text")
                        )
                    )

                    "tool" -> add(
                        AssistantToolPart(
                            id = id,
                            toolName = item.optString("toolName"),
                            toolArguments = item.optString("toolArguments"),
                            result = item.optString("result")
                        )
                    )
                }
            }
        }
    }

    private fun deserializeToolCalls(toolCallsArray: JSONArray?): List<AiToolCall> {
        if (toolCallsArray == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until toolCallsArray.length()) {
                val item = toolCallsArray.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                if (id.isBlank() || name.isBlank()) {
                    continue
                }
                add(
                    AiToolCall(
                        id = id,
                        name = name,
                        arguments = item.optString("arguments")
                    )
                )
            }
        }
    }
}
