package com.mukapp.mote

import android.content.Intent
import android.graphics.Typeface
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.ViewTreeObserver
import android.widget.ScrollView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatTextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mukapp.mote.ui.markdown.StreamingMarkdownRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FreeCopyActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun plainTextPreservesFullUnicodeAndLineEndingsAndRemainsSelectableAndScrollable() {
        val content = "中文🙂\r\n".repeat(16_666) + "尾文\r\n"
        assertEquals(100_000, content.length)

        withRenderedActivity(content, plainText = true, expectedText = content) { activity, textView ->
            instrumentation.runOnMainSync {
                assertEquals(content, textView.text.toString())
                assertTrue("全文仍应允许选择复制", textView.isTextSelectable)
            }
            assertScrollable(activity)
        }
    }

    @Test
    fun markdownMatchesLegacyStandaloneTextAndStyleAndLinkSemantics() {
        val content = """
            # 标题 中文

            **加粗**与 *斜体*，[链接](https://example.com/docs?q=中文)。
            行内公式 ${'$'}x^2 + y^2${'$'} 和 \(a+b\)。

            > 引用 **粗体引用**

            ```kotlin
            val greeting = "中文🙂"
            println(greeting)
            ```

            | 名称 | 值 |
            | --- | --- |
            | 中文 | 🙂 |

            - 列表项
            - [x] 已完成

            ---

            ${'$'}${'$'}
            \frac{a}{b}
            ${'$'}${'$'}

            \[
            c^2 = a^2 + b^2
            \]
        """.trimIndent()
        lateinit var expected: Spanned
        instrumentation.runOnMainSync {
            // 使用旧同步入口，不调用后台 prepare；对照 Main 上原有渲染的复制与样式语义。
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Mote)
            expected = StreamingMarkdownRenderer(context).apply { standalone = true }.renderStatic(content)
            assertTrue(styles(expected).any {
                it.style == Typeface.BOLD && expected.subSequence(it.start, it.end).toString() == "标题 中文"
            })
            assertTrue(styles(expected).any {
                it.style == Typeface.ITALIC && expected.subSequence(it.start, it.end).toString() == "斜体"
            })
            assertEquals(listOf("https://example.com/docs?q=中文"), links(expected).map { it.url })
            assertTrue(expected.toString().contains("${'$'}x^2 + y^2${'$'}"))
            assertTrue(expected.toString().contains("\\frac{a}{b}"))
            assertTrue(expected.toString().contains("c^2 = a^2 + b^2"))
        }

        withRenderedActivity(content, plainText = false, expectedText = expected.toString()) { _, textView ->
            instrumentation.runOnMainSync {
                val actual = textView.text as Spanned
                assertEquals(expected.toString(), actual.toString())
                assertEquals(styles(expected), styles(actual))
                assertEquals(links(expected), links(actual))
                assertTrue("Markdown 全文仍应允许选择复制", textView.isTextSelectable)
            }
        }
    }

    private fun withRenderedActivity(
        content: String,
        plainText: Boolean,
        expectedText: String,
        verify: (FreeCopyActivity, AppCompatTextView) -> Unit
    ) {
        val intent = Intent(instrumentation.targetContext, FreeCopyActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(FreeCopyActivity.EXTRA_CONTENT, content)
            .putExtra("extra_plain_text", plainText)
        val activity = instrumentation.startActivitySync(intent) as FreeCopyActivity
        val rendered = CountDownLatch(1)
        var textView: AppCompatTextView? = null
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (s?.toString() == expectedText) rendered.countDown()
            }
        }
        try {
            instrumentation.runOnMainSync {
                val view = activity.findViewById<AppCompatTextView>(R.id.text_content)
                textView = view
                // 同一 Main 回调先订阅，再读取；覆盖 startActivitySync 返回前已完成的情况。
                view.addTextChangedListener(watcher)
                if (view.text.toString() == expectedText) rendered.countDown()
            }
            assertTrue("10 秒内应绑定完整文本", rendered.await(10, TimeUnit.SECONDS))
            verify(activity, requireNotNull(textView))
        } finally {
            instrumentation.runOnMainSync {
                textView?.removeTextChangedListener(watcher)
                activity.finish()
            }
        }
    }

    private fun assertScrollable(activity: FreeCopyActivity) {
        val laidOut = CountDownLatch(1)
        lateinit var scrollView: ScrollView
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (scrollView.canScrollVertically(1)) laidOut.countDown()
        }
        try {
            instrumentation.runOnMainSync {
                scrollView = activity.findViewById(R.id.scroll_content)
                scrollView.viewTreeObserver.addOnGlobalLayoutListener(listener)
                if (scrollView.canScrollVertically(1)) laidOut.countDown()
            }
            assertTrue("完整文本布局后应能向下滚动", laidOut.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertTrue(scrollView.canScrollVertically(1))
                scrollView.scrollTo(0, scrollView.getChildAt(0).height)
                assertTrue("应实际滚到全文下方", scrollView.scrollY > 0)
                assertTrue(scrollView.canScrollVertically(-1))
            }
        } finally {
            instrumentation.runOnMainSync {
                scrollView.viewTreeObserver.removeOnGlobalLayoutListener(listener)
            }
        }
    }

    private data class StyleSemantic(val start: Int, val end: Int, val style: Int, val flags: Int)
    private data class LinkSemantic(val start: Int, val end: Int, val url: String, val flags: Int)

    private fun styles(text: Spanned): List<StyleSemantic> =
        text.getSpans(0, text.length, StyleSpan::class.java).map {
            StyleSemantic(text.getSpanStart(it), text.getSpanEnd(it), it.style, text.getSpanFlags(it))
        }.sortedWith(compareBy({ it.start }, { it.end }, { it.style }, { it.flags }))

    private fun links(text: Spanned): List<LinkSemantic> =
        text.getSpans(0, text.length, URLSpan::class.java).map {
            LinkSemantic(text.getSpanStart(it), text.getSpanEnd(it), it.url, text.getSpanFlags(it))
        }.sortedWith(compareBy({ it.start }, { it.end }, { it.url }, { it.flags }))
}
