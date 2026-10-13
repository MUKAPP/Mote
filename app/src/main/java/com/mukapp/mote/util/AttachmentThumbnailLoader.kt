package com.mukapp.mote.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import android.widget.ImageView
import com.mukapp.mote.data.ChatHistoryStore
import com.mukapp.mote.data.model.ChatAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 附件图片缩略图加载器。
 *
 * 从附件内联数据或当前会话 blob 在 IO 解码缩略图，以会话、附件、引用与尺寸共同缓存；
 * tag 校验目标 ImageView 是否仍指向同一附件，避免 RecyclerView 复用时图片错位。
 * 圆角由 ShapeableImageView 自行裁剪，这里只负责提供原始 Bitmap。
 */
object AttachmentThumbnailLoader {

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 为 [target] 加载 [attachment] 的缩略图，目标边长约 [sizePx] 像素。
     * 命中缓存时同步设置；否则在 IO 线程解码后再回填。
     */
    fun load(target: ImageView, attachment: ChatAttachment, sizePx: Int, conversationId: String) {
        val key = "$conversationId:${attachment.id}:${attachment.base64Ref.orEmpty()}:$sizePx"
        target.tag = key
        target.setImageDrawable(null)
        cache.get(key)?.let {
            target.setImageBitmap(it)
            return
        }
        val context = target.context.applicationContext
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val base64 = attachment.base64Data?.takeIf { it.isNotBlank() }
                        ?: ChatHistoryStore.readAttachmentBase64(context, conversationId,
                            attachment.base64Ref ?: throw IllegalStateException("附件缺少内容引用。"))
                    decode(base64, sizePx).also {
                        if (it == null) MoteLog.w("Attachment", "附件缩略图解码失败。")
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    MoteLog.w("Attachment", "附件缩略图内容缺失或不可读。")
                    null
                }
            } ?: return@launch
            cache.put(key, bitmap)
            if (target.tag == key) target.setImageBitmap(bitmap)
        }
    }

    private fun decode(base64: String, sizePx: Int): Bitmap? {
        return runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, sizePx)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
    }

    /** 让较短边降采样后仍不小于目标尺寸，保证 centerCrop 铺满时不发虚。 */
    private fun computeInSampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) {
            return 1
        }
        val minDimension = minOf(width, height)
        var sample = 1
        while (minDimension / (sample * 2) >= target) {
            sample *= 2
        }
        return sample
    }
}
