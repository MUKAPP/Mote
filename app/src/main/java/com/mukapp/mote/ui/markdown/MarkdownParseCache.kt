package com.mukapp.mote.ui.markdown

import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局 Markdown 解析缓存。
 *
 * - 线程安全：内部使用 [ConcurrentHashMap]，后台解析在 [Dispatchers.Default] 上运行。
 * - [get] 提供同步查询，MarkdownView 在 onBind 时先查此缓存，命中则跳过主线程解析。
 * - [preparseAll] 在后台批量解析文本并写入缓存。
 * - 流式场景下变化频繁的最后一个 part 通常缓存未命中，会回退到 MarkdownView 内部的同步解析；
 *   但已经完成的前部 part 和历史消息会从缓存命中，避免主线程重复解析。
 */
class MarkdownParseCache {

    /** 同一文本只保留一个解析变体；isStreaming 只影响尾部未闭合结构的解析结果。 */
    private class CacheEntry(val isStreaming: Boolean, val result: BlockParser.ParseResult)

    private val cacheLock = Any()
    // 按字符数而非条数淘汰：ParseResult 内存占用与文本长度近似成正比，
    // 按条数上限会让少量超长消息占用不成比例的内存。
    private val cache = object : LruCache<String, CacheEntry>(MaxCacheChars) {
        override fun sizeOf(key: String, value: CacheEntry): Int = key.length.coerceAtLeast(1)
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val blockParser = BlockParser()

    /** 预解析任务的 Job，用于外部取消。仅在主线程访问。 */
    private var batchJob: Job? = null

    /**
     * 同步查询缓存。
     * @return 解析结果；缓存未命中或解析变体不匹配时返回 null（写入方会以新变体替换旧条目，
     * 避免同一文本的流式/非流式两份结果长期共存）。
     */
    fun get(text: String, isStreaming: Boolean): BlockParser.ParseResult? {
        val entry = synchronized(cacheLock) { cache.get(text) } ?: return null
        return if (entry.isStreaming == isStreaming) entry.result else null
    }

    /**
     * 批量后台预解析多条文本。在切换对话或加载历史消息时调用。
     * 必须在主线程调用（操作 [batchJob]）。
     *
     * @param scope      协程作用域
     * @param entries    待解析的 (text, isStreaming) 列表
     * @param onAllReady 全部解析完成后在主线程回调（可选）
     */
    fun preparseAll(
        scope: CoroutineScope,
        entries: List<Pair<String, Boolean>>,
        onAllReady: (() -> Unit)? = null
    ) {
        batchJob?.cancel()
        val toResolve = entries.asSequence()
            .filter { (text, _) -> text.isNotBlank() }
            .distinctBy { (text, _) -> text }
            .filterNot { (text, isStreaming) -> contains(text, isStreaming) }
            .toList()
        if (toResolve.isEmpty()) {
            onAllReady?.invoke()
            return
        }
        batchJob = scope.launch(Dispatchers.Default) {
            for ((text, isStreaming) in toResolve) {
                if (!inFlight.add(text)) {
                    continue
                }
                try {
                    if (!contains(text, isStreaming)) {
                        val result = blockParser.parseWithLinkDefs(text, isStreaming)
                        put(text, isStreaming, result)
                    }
                } finally {
                    inFlight.remove(text)
                }
                yield()
            }
            if (onAllReady != null) {
                launch(Dispatchers.Main.immediate) { onAllReady() }
            }
        }
    }

    /**
     * 清除所有缓存。在全局配置变化（如主题切换）时调用。
     * 必须在主线程调用（操作 [batchJob]）。
     */
    fun clear() {
        batchJob?.cancel()
        synchronized(cacheLock) { cache.evictAll() }
        inFlight.clear()
    }

    private fun contains(text: String, isStreaming: Boolean): Boolean {
        return synchronized(cacheLock) { cache.get(text)?.isStreaming == isStreaming }
    }

    private fun put(text: String, isStreaming: Boolean, result: BlockParser.ParseResult) {
        synchronized(cacheLock) { cache.put(text, CacheEntry(isStreaming, result)) }
    }

    private companion object {
        /** 缓存总字符预算；普通消息文本约 1..4K 字符，大致相当于数十到上百条消息。 */
        const val MaxCacheChars = 400_000
    }
}
