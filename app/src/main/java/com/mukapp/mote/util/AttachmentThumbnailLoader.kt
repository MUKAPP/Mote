package com.mukapp.mote.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import android.widget.ImageView
import com.mukapp.mote.data.model.ChatAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 附件图片缩略图加载器。
 *
 * 从附件的 base64 数据解码出降采样后的缩略图，按附件 id 缓存；异步加载完成后通过
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
    fun load(target: ImageView, attachment: ChatAttachment, sizePx: Int) {
        val id = attachment.id
        target.tag = id

        cache.get(id)?.let {
            target.setImageBitmap(it)
            return
        }

        val base64 = attachment.base64Data
        if (base64.isNullOrBlank()) {
            return
        }

        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(base64, sizePx) } ?: return@launch
            cache.put(id, bitmap)
            if (target.tag == id) {
                target.setImageBitmap(bitmap)
            }
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
