package com.mukapp.mote

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.mukapp.mote.databinding.ActivityFreeCopyBinding
import com.mukapp.mote.ui.markdown.StreamingMarkdownRenderer
import com.mukapp.mote.util.dpInt

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
        renderContent(intent.getStringExtra(EXTRA_CONTENT).orEmpty())
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

    private fun renderContent(content: String) {
        val renderer = StreamingMarkdownRenderer(this)
        // 自由复制页把表格渲染为纯文本网格，确保可选取复制（Canvas 表格无法选择）
        renderer.tablesAsPlainText = true
        // 启用贴近聊天页 MarkdownView 的整篇富样式
        renderer.standalone = true
        binding.textContent.text = renderer.renderStatic(content)
    }

    companion object {
        const val EXTRA_CONTENT = "extra_content"
    }
}
