package com.mukapp.mote

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.fragment.app.commitNow
import com.google.android.material.color.MaterialColors
import com.mukapp.mote.databinding.ActivityMainBinding
import com.mukapp.mote.ui.ChatFragment
import com.mukapp.mote.ui.ChatViewModel
import com.mukapp.mote.util.dpInt
import eightbitlab.com.blurview.BlurTarget

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupInsets()
        setupChrome()
        setupNavigation()
        setupBackPressHandler()

        if (savedInstanceState == null) {
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

            supportFragmentManager.commit {
                replace(R.id.fragment_container, ChatFragment(), ChatTag)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.reloadSettings()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.blurViewToolbar.updatePadding(top = systemBars.top)
            binding.navView.updatePadding(top = systemBars.top, bottom = systemBars.bottom)
            insets
        }
    }

    private fun setupChrome() {
        binding.toolbar.setTitle(R.string.title_chat)
        binding.toolbar.setNavigationIcon(R.drawable.ic_menu)
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    private fun setupNavigation() {
        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_new_chat -> {
                    viewModel.clearConversation()
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }

                R.id.nav_settings -> {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }

                else -> false
            }
        }
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
