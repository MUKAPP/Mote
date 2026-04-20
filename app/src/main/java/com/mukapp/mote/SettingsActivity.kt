package com.mukapp.mote

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.mukapp.mote.data.ApiSettingsStore
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.databinding.ActivitySettingsBinding
import com.mukapp.mote.util.hasManageAllFilesPermission
import com.mukapp.mote.util.openManageAllFilesAccessSettings

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private var selectedReasoningEffort: String = "high"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupChrome()
        setupInsets()
        setupActions()
        applySettings(ApiSettingsStore.load(this))
        refreshPermissionState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        applySettings(ApiSettingsStore.load(this))
    }

    private fun setupChrome() {
        binding.toolbar.setTitle(R.string.title_settings)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = systemBars.top)
            binding.settingsContent.root.updatePadding(bottom = systemBars.bottom)
            insets
        }
    }

    private fun setupActions() {
        binding.settingsContent.buttonOpenPermissionSettings.setOnClickListener {
            openManageAllFilesAccessSettings(this)
        }
        binding.settingsContent.buttonSaveSettings.setOnClickListener {
            val settings = ApiSettings(
                baseUrl = binding.settingsContent.editBaseUrl.text?.toString().orEmpty().trim(),
                apiKey = binding.settingsContent.editApiKey.text?.toString().orEmpty().trim(),
                model = binding.settingsContent.editModel.text?.toString().orEmpty().trim(),
                reasoningEffort = selectedReasoningEffort
            )
            ApiSettingsStore.save(this, settings)
            binding.settingsContent.textSaveMessage.text = getString(R.string.settings_saved)
            binding.settingsContent.textSaveMessage.isVisible = true
        }
        binding.settingsContent.toggleReasoningEffort.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            selectedReasoningEffort = when (checkedId) {
                R.id.button_effort_low -> "low"
                R.id.button_effort_medium -> "medium"
                R.id.button_effort_high -> "high"
                R.id.button_effort_xhigh -> "xhigh"
                else -> selectedReasoningEffort
            }
            binding.settingsContent.textSaveMessage.isVisible = false
        }
        val hideSavedMessage = {
            binding.settingsContent.textSaveMessage.isVisible = false
        }
        binding.settingsContent.editBaseUrl.doAfterTextChanged { hideSavedMessage() }
        binding.settingsContent.editApiKey.doAfterTextChanged { hideSavedMessage() }
        binding.settingsContent.editModel.doAfterTextChanged { hideSavedMessage() }
    }

    private fun applySettings(settings: ApiSettings) {
        if (binding.settingsContent.editBaseUrl.text?.toString() != settings.baseUrl) {
            binding.settingsContent.editBaseUrl.setText(settings.baseUrl)
        }
        if (binding.settingsContent.editApiKey.text?.toString() != settings.apiKey) {
            binding.settingsContent.editApiKey.setText(settings.apiKey)
        }
        if (binding.settingsContent.editModel.text?.toString() != settings.model) {
            binding.settingsContent.editModel.setText(settings.model)
        }

        selectedReasoningEffort = settings.reasoningEffort.ifBlank { "high" }
        binding.settingsContent.toggleReasoningEffort.check(
            when (selectedReasoningEffort) {
                "low" -> R.id.button_effort_low
                "medium" -> R.id.button_effort_medium
                "xhigh" -> R.id.button_effort_xhigh
                else -> R.id.button_effort_high
            }
        )
    }

    private fun refreshPermissionState() {
        val granted = hasManageAllFilesPermission()
        val cardColor = MaterialColors.getColor(
            binding.settingsContent.cardPermission,
            if (granted) com.google.android.material.R.attr.colorSecondaryContainer else com.google.android.material.R.attr.colorErrorContainer
        )
        val titleColor = MaterialColors.getColor(
            binding.settingsContent.cardPermission,
            if (granted) com.google.android.material.R.attr.colorOnSecondaryContainer else com.google.android.material.R.attr.colorOnErrorContainer
        )
        binding.settingsContent.cardPermission.setCardBackgroundColor(cardColor)
        binding.settingsContent.textPermissionTitle.setTextColor(titleColor)
        binding.settingsContent.textPermissionDescription.setTextColor(titleColor)
        binding.settingsContent.textPermissionDescription.text = getString(
            if (granted) R.string.settings_permission_granted else R.string.settings_permission_denied
        )
        binding.settingsContent.buttonOpenPermissionSettings.text = getString(
            if (granted) R.string.settings_permission_reopen else R.string.settings_permission_open
        )
    }
}
