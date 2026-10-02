package com.mukapp.mote

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.text.PrecomputedTextCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.mukapp.mote.databinding.ActivityFreeCopyBinding
import com.mukapp.mote.ui.markdown.StreamingMarkdownRenderer
import com.mukapp.mote.util.MoteLog
import com.mukapp.mote.util.dpInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自由复制页：使用旧的「仅 TextView」Markdown 渲染（[StreamingMarkdownRenderer]）将内容渲染为
 * 可选择文本的 [android.widget.TextView]，便于用户自由选取并复制片段。
 */
class FreeCopyActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFreeCopyBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityFreeCopyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupChrome()
        setupInsets()
        renderContent(
            content = intent.getStringExtra(EXTRA_CONTENT).orEmpty(),
            plainText = intent.getBooleanExtra(EXTRA_PLAIN_TEXT, false)
        )
    }

    private fun setupChrome() {
        binding.toolbar.setTitle(R.string.title_free_copy)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val fallbackSurfaceColor = ContextCompat.getColor(this, R.color.mote_background)
        val frameClearDrawable = window.decorView.background ?: fallbackSurfaceColor.toDrawable()
        val blurBaseColor = ContextCompat.getColor(this, R.color.mote_background)
        val overlayColor = ColorUtils.setAlphaComponent(blurBaseColor, (255 * 0.6f).toInt())
        binding.blurViewToolbar.setupWith(binding.blurTarget)
            .setFrameClearDrawable(frameClearDrawable)
            .setBlurRadius(20f)
            .setOverlayColor(overlayColor)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val toolbarHeight = binding.toolbar.minimumHeight.takeIf { it > 0 } ?: 56.dpInt
            binding.blurViewToolbar.updatePadding(top = systemBars.top)
            binding.scrollContent.updatePadding(
                top = systemBars.top + toolbarHeight + 16.dpInt,
                bottom = systemBars.bottom
            )
            insets
        }
    }

    private fun renderContent(content: String, plainText: Boolean) {
        lifecycleScope.launch {
            try {
                val metrics = TextViewCompat.getTextMetricsParams(binding.textContent)
                // 每页独占 renderer，Main 捕获主题与尺寸后，后台不再访问 View 或主题。
                val renderer = if (plainText) null else StreamingMarkdownRenderer(this@FreeCopyActivity).apply {
                    prepareForBackgroundRendering()
                }
                val preparedText = withContext(Dispatchers.Default) {
                    // Markdown 保留富样式、表格纯文本网格和公式原文，完整计算后才绑定。
                    val text = renderer?.renderStatic(content) ?: content
                    PrecomputedTextCompat.create(text, metrics)
                }
                TextViewCompat.setPrecomputedText(binding.textContent, preparedText)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                coroutineContext.ensureActive()
                MoteLog.e("FreeCopy", "自由复制内容排版失败", error)
                binding.textContent.text = content
            }
        }
    }

    companion object {
        const val EXTRA_CONTENT = "extra_content"
        private const val EXTRA_PLAIN_TEXT = "extra_plain_text"

        /** 以只读方式打开自由复制页；[plainText] 为 true 时保持原始字符且后台预计算布局。 */
        fun start(context: Context, content: String, plainText: Boolean = false) {
            context.startActivity(
                Intent(context, FreeCopyActivity::class.java)
                    .putExtra(EXTRA_CONTENT, content)
                    .putExtra(EXTRA_PLAIN_TEXT, plainText)
            )
        }
    }
}
