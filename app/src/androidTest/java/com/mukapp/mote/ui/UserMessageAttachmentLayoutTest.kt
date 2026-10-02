package com.mukapp.mote.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mukapp.mote.FreeCopyActivity
import com.mukapp.mote.R
import com.mukapp.mote.data.model.ChatAttachment
import com.mukapp.mote.data.model.ChatAttachmentType
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.data.model.ChatRole
import com.mukapp.mote.util.dpInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UserMessageAttachmentLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val twelveLines = (1..12).joinToString("\n") { "第${it}行" }

    @Test
    fun foldedAndExpandedAttachmentsAvoidButtonAndFullTouchAreaAtHorizontalEnd() {
        val message = userMessage(twelveLines, fileAttachments())
        withRecyclerView(message) { recyclerView, adapter ->
            awaitBoundMessage(recyclerView, message, toggleVisible = true)
            instrumentation.runOnMainSync {
                val holder = boundHolder(recyclerView)
                assertEquals(10, holder.itemView.findViewById<TextView>(R.id.text_content).maxLines)
                scrollAttachmentsAndAssertNoOverlap(recyclerView, holder)
                tapExpandedTouchArea(holder)
                assertEquals(Int.MAX_VALUE, holder.itemView.findViewById<TextView>(R.id.text_content).maxLines)
                assertSame("点击应改变同一条消息，而非替换数据", message, adapter.getMessageAt(0))
            }
            awaitBoundMessage(recyclerView, message, toggleVisible = true)
            instrumentation.runOnMainSync {
                val holder = boundHolder(recyclerView)
                scrollAttachmentsAndAssertNoOverlap(recyclerView, holder)
                tapExpandedTouchArea(holder)
                assertEquals(10, holder.itemView.findViewById<TextView>(R.id.text_content).maxLines)
                assertSame(message, adapter.getMessageAt(0))
            }
            awaitBoundMessage(recyclerView, message, toggleVisible = true)
            instrumentation.runOnMainSync {
                scrollAttachmentsAndAssertNoOverlap(recyclerView, boundHolder(recyclerView))
            }
        }
    }

    @Test
    fun shortAttachmentsHaveNoToggleAndLongTextWithoutAttachmentsKeepsCollapsedRule() {
        val short = userMessage("短消息", fileAttachments())
        withRecyclerView(short) { recyclerView, adapter ->
            awaitBoundMessage(recyclerView, short, toggleVisible = false)
            instrumentation.runOnMainSync {
                val root = boundHolder(recyclerView).itemView
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.scroll_attachments).visibility)
                assertEquals(16.dpInt, root.findViewById<View>(R.id.container_content).paddingBottom)
                assertNull(root.findViewById<View>(R.id.card_message).touchDelegate)
                adapter.submitMessages(listOf(short.copy(content = twelveLines, attachments = emptyList())), false)
            }
            val long = short.copy(content = twelveLines, attachments = emptyList())
            awaitBoundMessage(recyclerView, long, toggleVisible = true)
            instrumentation.runOnMainSync {
                val root = boundHolder(recyclerView).itemView
                assertEquals(View.GONE, root.findViewById<View>(R.id.scroll_attachments).visibility)
                assertEquals(10, root.findViewById<TextView>(R.id.text_content).maxLines)
                assertEquals("无附件折叠态仍允许覆盖省略号", 16.dpInt,
                    root.findViewById<View>(R.id.container_content).paddingBottom)
                assertTrue(root.findViewById<ImageView>(R.id.btn_toggle_expand).performClick())
                assertEquals(Int.MAX_VALUE, root.findViewById<TextView>(R.id.text_content).maxLines)
                assertEquals(48.dpInt, root.findViewById<View>(R.id.container_content).paddingBottom)
            }
        }
    }

    @Test
    fun rapidRebindAndActualRecyclingCannotRestoreStaleTouchDelegate() {
        val long = userMessage(twelveLines, fileAttachments())
        val short = long.copy(content = "短消息", attachments = emptyList())
        withRecyclerView(long) { recyclerView, adapter ->
            awaitBoundMessage(recyclerView, long, toggleVisible = true)
            lateinit var holder: RecyclerView.ViewHolder
            lateinit var card: View
            instrumentation.runOnMainSync {
                holder = boundHolder(recyclerView)
                card = holder.itemView.findViewById(R.id.card_message)
                assertNotNull(card.touchDelegate)
                // 在同一 Main 回调内通过真实 RecyclerView 布局连续重绑，post 尚无机会运行。
                adapter.submitMessages(listOf(long.copy(content = "$twelveLines\n末行")), false)
                layoutRecyclerView(recyclerView)
                assertSame(holder, boundHolder(recyclerView))
                adapter.submitMessages(listOf(short), false)
                layoutRecyclerView(recyclerView)
                assertSame(holder, boundHolder(recyclerView))
                assertEquals(View.GONE, holder.itemView.findViewById<View>(R.id.btn_toggle_expand).visibility)
                assertNull(card.touchDelegate)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(0, holder.bindingAdapterPosition)
                assertNull("短消息绑定后旧 post 不得重新安装 delegate", card.touchDelegate)
            }

            var recycled = false
            instrumentation.runOnMainSync {
                recyclerView.addRecyclerListener { if (it === holder) recycled = true }
                adapter.submitMessages(listOf(long), false)
                layoutRecyclerView(recyclerView)
                assertSame(holder, boundHolder(recyclerView))
                assertEquals(View.VISIBLE, holder.itemView.findViewById<View>(R.id.btn_toggle_expand).visibility)
                // 保留可见按钮并立即真实回收，验证不是依靠隐藏按钮来阻止迟到 Runnable。
                recyclerView.adapter = null
                assertTrue("应收到真实 RecyclerView 回收回调", recycled)
                assertEquals(RecyclerView.NO_POSITION, holder.bindingAdapterPosition)
                assertNull(card.touchDelegate)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertNull("回收后旧 post 不得重新安装 delegate", card.touchDelegate)
            }
        }
    }

    private fun userMessage(content: String, attachments: List<ChatAttachment>) =
        ChatMessage(role = ChatRole.User, content = content, attachments = attachments)

    private fun fileAttachments() = (1..20).map {
        ChatAttachment(
            type = ChatAttachmentType.File,
            displayName = "附件-$it.txt",
            path = "/未读取的测试附件/附件-$it.txt"
        )
    }

    private fun withRecyclerView(
        message: ChatMessage,
        verify: (RecyclerView, ChatMessageAdapter) -> Unit
    ) {
        val expected = "附件布局测试"
        val intent = Intent(instrumentation.targetContext, FreeCopyActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(FreeCopyActivity.EXTRA_CONTENT, expected)
            .putExtra("extra_plain_text", true)
        val activity = instrumentation.startActivitySync(intent) as FreeCopyActivity
        val rendered = CountDownLatch(1)
        var textView: AppCompatTextView? = null
        var recyclerView: RecyclerView? = null
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (s?.toString() == expected) rendered.countDown()
            }
        }
        try {
            instrumentation.runOnMainSync {
                val view = activity.findViewById<AppCompatTextView>(R.id.text_content)
                textView = view
                view.addTextChangedListener(watcher)
                if (view.text.toString() == expected) rendered.countDown()
            }
            assertTrue("10 秒内应绑定初次文本", rendered.await(10, TimeUnit.SECONDS))
            lateinit var adapter: ChatMessageAdapter
            instrumentation.runOnMainSync {
                adapter = ChatMessageAdapter({}, {}, {}, {}, {})
                val view = RecyclerView(activity).apply {
                    layoutManager = LinearLayoutManager(activity)
                    itemAnimator = null
                    setItemViewCacheSize(0)
                    this.adapter = adapter
                }
                recyclerView = view
                activity.setContentView(view, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                adapter.submitMessages(listOf(message), false)
            }
            verify(requireNotNull(recyclerView), adapter)
        } finally {
            instrumentation.runOnMainSync {
                textView?.removeTextChangedListener(watcher)
                recyclerView?.adapter = null
                activity.finish()
            }
        }
    }

    private fun awaitBoundMessage(recyclerView: RecyclerView, message: ChatMessage, toggleVisible: Boolean) {
        val ready = CountDownLatch(1)
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                checkReady()
                if (ready.count > 0) recyclerView.postInvalidateOnAnimation()
                return true
            }

            fun checkReady() {
                val holder = recyclerView.findViewHolderForAdapterPosition(0) ?: return
                if (recyclerView.isLayoutRequested || holder.itemView.isLayoutRequested) return
                if (holder.bindingAdapterPosition != 0) return
                val root = holder.itemView
                val text = root.findViewById<TextView>(R.id.text_content)
                val toggle = root.findViewById<View>(R.id.btn_toggle_expand)
                val card = root.findViewById<View>(R.id.card_message)
                if (text.text.toString() == message.content &&
                    (toggle.visibility == View.VISIBLE) == toggleVisible &&
                    (card.touchDelegate != null) == toggleVisible) {
                    ready.countDown()
                }
            }
        }
        try {
            instrumentation.runOnMainSync {
                recyclerView.viewTreeObserver.addOnPreDrawListener(listener)
                listener.checkReady()
                recyclerView.postInvalidateOnAnimation()
            }
            assertTrue("10 秒内应完成真实 holder 绑定与触控区域安装", ready.await(10, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync {
                recyclerView.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        }
    }

    private fun boundHolder(recyclerView: RecyclerView): RecyclerView.ViewHolder {
        val holder = requireNotNull(recyclerView.findViewHolderForAdapterPosition(0))
        assertEquals(0, holder.bindingAdapterPosition)
        return holder
    }

    private fun layoutRecyclerView(recyclerView: RecyclerView) {
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(recyclerView.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(recyclerView.height, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(recyclerView.left, recyclerView.top, recyclerView.right, recyclerView.bottom)
    }

    private fun scrollAttachmentsAndAssertNoOverlap(recyclerView: RecyclerView, holder: RecyclerView.ViewHolder) {
        // 屏幕较矮时先把气泡底部滚入可见区，再检查附件横向末端。
        recyclerView.scrollBy(0, (holder.itemView.bottom - recyclerView.height).coerceAtLeast(0))
        val root = holder.itemView
        val scroll = root.findViewById<HorizontalScrollView>(R.id.scroll_attachments)
        assertTrue("20 个文件附件应可横向滚动", scroll.canScrollHorizontally(1) || scroll.scrollX > 0)
        scroll.isSmoothScrollingEnabled = false
        scroll.fullScroll(View.FOCUS_RIGHT)
        assertTrue(scroll.scrollX > 0)
        assertFalse("应到达附件末端", scroll.canScrollHorizontally(1))
        val attachments = root.findViewById<LinearLayout>(R.id.container_attachments)
        assertEquals(20, attachments.childCount)
        val button = visibleRect(root.findViewById(R.id.btn_toggle_expand))
        val card = visibleRect(root.findViewById(R.id.card_message))
        val bottomEndTouchArea = Rect(card.right - 48.dpInt, card.bottom - 48.dpInt, card.right, card.bottom)
        val last = visibleRect(attachments.getChildAt(attachments.childCount - 1))
        assertFalse("末端附件不得被按钮遮挡", Rect.intersects(last, button))
        assertFalse("末端附件须避让完整 48dp 底部触控区", Rect.intersects(last, bottomEndTouchArea))
        for (index in 0 until attachments.childCount) {
            val attachmentRect = Rect()
            if (attachments.getChildAt(index).getGlobalVisibleRect(attachmentRect)) {
                assertFalse(Rect.intersects(attachmentRect, button))
                assertFalse(Rect.intersects(attachmentRect, bottomEndTouchArea))
            }
        }
    }

    private fun visibleRect(view: View): Rect = Rect().also {
        assertTrue("待检查控件必须在屏幕上可见", view.getGlobalVisibleRect(it))
    }

    private fun tapExpandedTouchArea(holder: RecyclerView.ViewHolder) {
        val card = holder.itemView.findViewById<View>(R.id.card_message)
        val button = holder.itemView.findViewById<View>(R.id.btn_toggle_expand)
        val buttonRect = Rect().also(button::getHitRect)
        // 点击视觉按钮上方但仍位于 48dp 扩展区，验证真实 TouchDelegate 而非直接调用 listener。
        val x = buttonRect.exactCenterX()
        val y = buttonRect.top - 6.dpInt.toFloat()
        val downTime = SystemClock.uptimeMillis()
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            try {
                assertTrue("扩展点击区应消费触摸", card.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
    }
}
