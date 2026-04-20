package com.mukapp.mote

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.commit
import com.mukapp.mote.databinding.ActivityMainBinding
import com.mukapp.mote.ui.ChatFragment
import com.mukapp.mote.ui.ChatViewModel

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
            binding.toolbar.updatePadding(top = systemBars.top)
            // binding.fragmentContainer.updatePadding(bottom = systemBars.bottom)
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
