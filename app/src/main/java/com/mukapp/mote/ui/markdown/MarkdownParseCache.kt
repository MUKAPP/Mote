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
 * - [preparse] 在后台解析指定文本并写入缓存；调用方可选择在解析完成后回调通知 UI 刷新。
 * - 流式场景下变化频繁的最后一个 part 通常缓存未命中，会回退到 MarkdownView 内部的同步解析；
 *   但已经完成的前部 part 和历史消息会从缓存命中，避免主线程重复解析。
 */
class MarkdownParseCache {

    private data class CacheKey(val text: String, val isStreaming: Boolean)

    private val cacheLock = Any()
    private val cache = object : LruCache<CacheKey, BlockParser.ParseResult>(MaxCacheEntries) {
        override fun sizeOf(key: CacheKey, value: BlockParser.ParseResult): Int = 1
    }
    private val inFlight = ConcurrentHashMap.newKeySet<CacheKey>()
    private val blockParser = BlockParser()

    /** 预解析任务的 Job，用于外部取消。仅在主线程访问。 */
    private var batchJob: Job? = null

    /**
     * 同步查询缓存。
     * @return 解析结果；缓存未命中时返回 null。
     */
    fun get(text: String, isStreaming: Boolean): BlockParser.ParseResult? {
        val key = CacheKey(text, isStreaming)
        return synchronized(cacheLock) { cache.get(key) }
    }

    /**
     * 在后台线程解析 [text] 并写入缓存。
     *
     * @param scope   协程作用域，通常为 viewLifecycleOwner.lifecycleScope
     * @param text    待解析的 Markdown 文本
     * @param isStreaming 当前是否处于流式状态
     * @param onReady 解析完成后在主线程回调（可选），用于通知 RecyclerView 刷新
     */
    fun preparse(
        scope: CoroutineScope,
        text: String,
        isStreaming: Boolean,
        onReady: (() -> Unit)? = null
    ) {
        val key = CacheKey(text, isStreaming)
        if (contains(key)) {
            onReady?.invoke()
            return
        }
        if (!inFlight.add(key)) {
            return
        }
        scope.launch(Dispatchers.Default) {
            try {
                val result = blockParser.parseWithLinkDefs(text, isStreaming)
                put(key, result)
                if (onReady != null) {
                    launch(Dispatchers.Main.immediate) { onReady() }
                }
            } finally {
                inFlight.remove(key)
            }
        }
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
            .distinctBy { (text, isStreaming) -> CacheKey(text, isStreaming) }
            .filterNot { (text, isStreaming) -> contains(CacheKey(text, isStreaming)) }
            .toList()
        if (toResolve.isEmpty()) {
            onAllReady?.invoke()
            return
        }
        batchJob = scope.launch(Dispatchers.Default) {
            for ((text, isStreaming) in toResolve) {
                val key = CacheKey(text, isStreaming)
                if (!inFlight.add(key)) {
                    continue
                }
                try {
                    if (!contains(key)) {
                        val result = blockParser.parseWithLinkDefs(text, isStreaming)
                        put(key, result)
                    }
                } finally {
                    inFlight.remove(key)
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

    /**
     * 淘汰不再使用的缓存条目，保留指定的活跃文本。
     * 避免内存无限增长。
     */
    fun evict(activeTexts: Set<String>) {
        val keysToRemove = synchronized(cacheLock) {
            cache.snapshot().keys.filter { it.text !in activeTexts }
        }
        synchronized(cacheLock) {
            keysToRemove.forEach { cache.remove(it) }
        }
    }

    private fun contains(key: CacheKey): Boolean {
        return synchronized(cacheLock) { cache.get(key) != null }
    }

    private fun put(key: CacheKey, result: BlockParser.ParseResult) {
        synchronized(cacheLock) { cache.put(key, result) }
    }

    private companion object {
        const val MaxCacheEntries = 160
    }
}
