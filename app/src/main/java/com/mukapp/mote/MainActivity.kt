package com.mukapp.mote

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.mukapp.mote.databinding.ActivityMainBinding
import com.mukapp.mote.data.model.findProvider
import com.mukapp.mote.data.model.resolvedChatModel
import com.mukapp.mote.ui.ChatFragment
import com.mukapp.mote.ui.ChatViewModel
import com.mukapp.mote.ui.ConversationSummaryAdapter
import com.mukapp.mote.ui.ModelPickerBottomSheet
import com.mukapp.mote.util.dpInt
import eightbitlab.com.blurview.BlurTarget
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var conversationAdapter: ConversationSummaryAdapter
    private var latestConversationId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupInsets()
        setupChrome()
        setupNavigation()
        observeConversations()
        observeModelSelector()
        setupBackPressHandler()

        supportFragmentManager.registerFragmentLifecycleCallbacks(object :
            FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(
                fm: FragmentManager,
                f: Fragment,
                v: View,
                savedInstanceState: Bundle?
            ) {
                if (f is ChatFragment) {
                    // 此时 Fragment 的视图已经创建，可以安全获取内部的目标容器
                    val blurTarget = v.findViewById<BlurTarget>(R.id.blur_target)

                    if (blurTarget != null) {
                        val realWindowBackground = this@MainActivity.window.decorView.background
                        val backgroundColorInt =
                            (realWindowBackground as? android.graphics.drawable.ColorDrawable)?.color
                                ?: ContextCompat.getColor(this@MainActivity, R.color.mote_background)
                        val overlayColor = ColorUtils.setAlphaComponent(
                            backgroundColorInt,
                            (255 * 0.6).toInt()
                        )
                        binding.blurViewToolbar.setupWith(blurTarget)
                            .setFrameClearDrawable(realWindowBackground)
                            .setBlurRadius(20f)
                            .setOverlayColor(overlayColor)
                    }
                }
            }
        }, false)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, ChatFragment(), ChatTag)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.reloadSettings()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_toolbar_menu, menu)
        menu.findItem(R.id.action_delete_conversation)?.isEnabled = viewModel.isSending.value != true
        return true
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.blurViewToolbar.updatePadding(top = systemBars.top)
            binding.drawerPanel.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }
    }

    private fun setupChrome() {
        binding.toolbar.setNavigationIcon(R.drawable.ic_menu)
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.modelSelector.setOnClickListener { showModelPicker() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete_conversation -> {
                    if (viewModel.isSending.value != true) {
                        showDeleteConversationDialog()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun showModelPicker() {
        val settings = viewModel.savedSettings.value ?: return
        ModelPickerBottomSheet.show(this, settings, settings.chatModel) { ref ->
            viewModel.selectChatModel(ref)
        }
    }

    private fun setupNavigation() {
        binding.drawerPanel.layoutParams = binding.drawerPanel.layoutParams.apply {
            width = min(resources.displayMetrics.widthPixels - 56.dpInt, 320.dpInt)
        }
        conversationAdapter = ConversationSummaryAdapter(
            onConversationClick = { summary ->
                viewModel.switchConversation(summary.id)
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            },
            onConversationLongClick = { summary ->
                showDeleteConversationDialog(summary.id, summary.title)
            }
        )
        binding.recyclerConversations.adapter = conversationAdapter
        binding.recyclerConversations.layoutManager = LinearLayoutManager(this)
        binding.recyclerConversations.itemAnimator = null

        binding.buttonNewChat.setOnClickListener {
            viewModel.startNewConversation()
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
        binding.buttonSettings.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun observeConversations() {
        viewModel.currentConversationId.observe(this) { conversationId ->
            latestConversationId = conversationId
            conversationAdapter.submitItems(
                viewModel.conversationSummaries.value.orEmpty(),
                latestConversationId
            )
        }
        viewModel.conversationSummaries.observe(this) { summaries ->
            conversationAdapter.submitItems(summaries, latestConversationId)
            binding.textHistoryEmpty.isVisible = summaries.isEmpty()
            binding.recyclerConversations.isVisible = summaries.isNotEmpty()
        }
        viewModel.isSending.observe(this) { sending ->
            binding.toolbar.menu.findItem(R.id.action_delete_conversation)?.isEnabled = !sending
        }
        viewModel.userNotice.observe(this) { message ->
            if (message.isNullOrBlank()) {
                return@observe
            }
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
            viewModel.clearUserNotice()
        }
    }

    private fun observeModelSelector() {
        viewModel.savedSettings.observe(this) { settings ->
            val label = settings?.resolvedChatModel()?.let { resolved ->
                settings.findProvider(settings.chatModel?.providerId)?.let { provider ->
                    val model = provider.models.firstOrNull { it.id == resolved.model }
                    model?.label ?: resolved.model
                } ?: resolved.model
            }
            binding.textModelSelector.text = label ?: getString(R.string.model_selector_unset)
        }
    }

    /** 删除当前对话（工具栏菜单触发） */
    private fun showDeleteConversationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_conversation_title)
            .setMessage(R.string.dialog_delete_conversation_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteCurrentConversation()
            }
            .show()
    }

    /** 删除指定对话（长按列表项触发） */
    private fun showDeleteConversationDialog(conversationId: String, title: String) {
        val displayTitle = title.ifBlank { getString(R.string.nav_untitled_chat) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_conversation_title)
            .setMessage(getString(R.string.dialog_delete_specific_conversation_message, displayTitle))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                if (conversationId == latestConversationId) {
                    viewModel.deleteCurrentConversation()
                } else {
                    viewModel.deleteConversation(conversationId)
                }
            }
            .show()
    }

    private fun setupBackPressHandler() {
        var hasDrawerBackProgress = false
        val closeDrawerCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) {
                hasDrawerBackProgress = false
                binding.drawerPanel.animate().cancel()
                binding.drawerPanel.translationX = 0f
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                hasDrawerBackProgress = true
                binding.drawerPanel.translationX = drawerCloseTranslation(backEvent.progress)
            }

            override fun handleOnBackCancelled() {
                hasDrawerBackProgress = false
                binding.drawerPanel.animate()
                    .translationX(0f)
                    .setDuration(DrawerBackCancelDurationMs)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            override fun handleOnBackPressed() {
                binding.drawerPanel.animate().cancel()
                binding.drawerLayout.closeDrawer(GravityCompat.START, !hasDrawerBackProgress)
                binding.drawerPanel.translationX = 0f
                hasDrawerBackProgress = false
            }
        }
        onBackPressedDispatcher.addCallback(this, closeDrawerCallback)

        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                if (drawerView == binding.drawerPanel) {
                    closeDrawerCallback.isEnabled = true
                    binding.drawerPanel.translationX = 0f
                }
            }

            override fun onDrawerClosed(drawerView: View) {
                if (drawerView == binding.drawerPanel) {
                    closeDrawerCallback.isEnabled = false
                    hasDrawerBackProgress = false
                    binding.drawerPanel.animate().cancel()
                    binding.drawerPanel.translationX = 0f
                }
            }
        })
    }

    private fun drawerCloseTranslation(progress: Float): Float {
        val direction = if (binding.drawerPanel.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            1f
        } else {
            -1f
        }
        return binding.drawerPanel.width * progress.coerceIn(0f, 1f) * direction
    }

    private companion object {
        const val ChatTag = "chat_fragment"
        const val DrawerBackCancelDurationMs = 120L
    }
}
