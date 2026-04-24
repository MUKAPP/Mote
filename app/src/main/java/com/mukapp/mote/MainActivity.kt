package com.mukapp.mote

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.mukapp.mote.databinding.ActivityMainBinding
import com.mukapp.mote.ui.ChatFragment
import com.mukapp.mote.ui.ChatViewModel
import com.mukapp.mote.ui.ConversationSummaryAdapter
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
                                ?: MaterialColors.getColor(
                                    binding.root,
                                    com.google.android.material.R.attr.colorSurface
                                )
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
        binding.toolbar.setTitle(R.string.title_chat)
        binding.toolbar.setNavigationIcon(R.drawable.ic_menu)
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
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

    private fun setupNavigation() {
        binding.drawerPanel.layoutParams = binding.drawerPanel.layoutParams.apply {
            width = min(resources.displayMetrics.widthPixels - 56.dpInt, 320.dpInt)
        }
        conversationAdapter = ConversationSummaryAdapter { summary ->
            viewModel.switchConversation(summary.id)
            binding.drawerLayout.closeDrawer(GravityCompat.START)
        }
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

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.drawerLayout.isDrawerOpen(GravityCompat.START) -> {
                        binding.drawerLayout.closeDrawer(GravityCompat.START)
                    }

                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private companion object {
        const val ChatTag = "chat_fragment"
    }
}
