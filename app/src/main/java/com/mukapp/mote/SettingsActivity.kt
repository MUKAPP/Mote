package com.mukapp.mote

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.color.MaterialColors
import com.mukapp.mote.data.ApiSettingsStore
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.databinding.ActivitySettingsBinding
import com.mukapp.mote.util.dpInt
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
    }

    private fun setupChrome() {
        binding.toolbar.setTitle(R.string.title_settings)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        val fallbackSurfaceColor = MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorSurface
        )
        val frameClearDrawable = window.decorView.background ?: fallbackSurfaceColor.toDrawable()
        val blurBaseColor = MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorSurfaceContainerLow
        )
        val overlayColor = ColorUtils.setAlphaComponent(blurBaseColor, (255 * 0.6f).toInt())
        binding.blurViewToolbar.setupWith(binding.blurTarget)
            .setFrameClearDrawable(frameClearDrawable)
            .setBlurRadius(20f)
            .setOverlayColor(overlayColor)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val toolbarHeight = binding.toolbar.minimumHeight.takeIf { it > 0 }
                ?: 56.dpInt
            binding.blurViewToolbar.updatePadding(top = systemBars.top)
            binding.settingsContent.root.updatePadding(
                top = systemBars.top + toolbarHeight + 16.dpInt,
                bottom = systemBars.bottom
            )
            insets
        }
    }

    private fun setupActions() {
        binding.settingsContent.buttonOpenPermissionSettings.setOnClickListener {
            openManageAllFilesAccessSettings(this)
        }
        binding.settingsContent.buttonSaveSettings.setOnClickListener {
            val modelContextLength = parseLengthInput(
                rawValue = binding.settingsContent.editModelContextLength.text?.toString(),
                onError = { binding.settingsContent.inputModelContextLength.error = it }
            ) ?: return@setOnClickListener
            val compressionTriggerLength = parseLengthInput(
                rawValue = binding.settingsContent.editCompressionTriggerLength.text?.toString(),
                onError = { binding.settingsContent.inputCompressionTriggerLength.error = it }
            ) ?: return@setOnClickListener
            if (modelContextLength > 0 && compressionTriggerLength > modelContextLength) {
                binding.settingsContent.inputCompressionTriggerLength.error =
                    getString(R.string.settings_compression_trigger_too_large)
                return@setOnClickListener
            }

            val settings = ApiSettings(
                baseUrl = binding.settingsContent.editBaseUrl.text?.toString().orEmpty().trim(),
                apiKey = binding.settingsContent.editApiKey.text?.toString().orEmpty().trim(),
                model = binding.settingsContent.editModel.text?.toString().orEmpty().trim(),
                titleModel = binding.settingsContent.editTitleModel.text?.toString().orEmpty()
                    .trim(),
                compressionModel = binding.settingsContent.editCompressionModel.text?.toString()
                    .orEmpty().trim(),
                modelContextLength = modelContextLength,
                compressionTriggerLength = compressionTriggerLength,
                searxngUrl = binding.settingsContent.editSearxngUrl.text?.toString().orEmpty()
                    .trim(),
                reasoningEffort = selectedReasoningEffort
            )
            ApiSettingsStore.save(this, settings)
            binding.settingsContent.textSaveMessage.text = getString(R.string.settings_saved)
            binding.settingsContent.textSaveMessage.isVisible = true
        }
        binding.settingsContent.toggleReasoningEffort.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
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
        val clearLengthErrors = {
            binding.settingsContent.inputModelContextLength.error = null
            binding.settingsContent.inputCompressionTriggerLength.error = null
            hideSavedMessage()
        }
        binding.settingsContent.editBaseUrl.doAfterTextChanged { hideSavedMessage() }
        binding.settingsContent.editApiKey.doAfterTextChanged { hideSavedMessage() }
        binding.settingsContent.editModel.doAfterTextChanged { hideSavedMessage() }
        binding.settingsContent.editTitleModel.doAfterTextChanged { hideSavedMessage() }
        binding.settingsContent.editCompressionModel.doAfterTextChanged { hideSavedMessage() }
        binding.settingsContent.editModelContextLength.doAfterTextChanged { clearLengthErrors() }
        binding.settingsContent.editCompressionTriggerLength.doAfterTextChanged { clearLengthErrors() }
        binding.settingsContent.editSearxngUrl.doAfterTextChanged { hideSavedMessage() }
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
        if (binding.settingsContent.editTitleModel.text?.toString() != settings.titleModel) {
            binding.settingsContent.editTitleModel.setText(settings.titleModel)
        }
        if (binding.settingsContent.editCompressionModel.text?.toString() != settings.compressionModel) {
            binding.settingsContent.editCompressionModel.setText(settings.compressionModel)
        }
        val modelContextLength = settings.modelContextLength.coerceAtLeast(0).toString()
        if (binding.settingsContent.editModelContextLength.text?.toString() != modelContextLength) {
            binding.settingsContent.editModelContextLength.setText(modelContextLength)
        }
        val compressionTriggerLength = settings.compressionTriggerLength.coerceAtLeast(0).toString()
        if (binding.settingsContent.editCompressionTriggerLength.text?.toString() != compressionTriggerLength) {
            binding.settingsContent.editCompressionTriggerLength.setText(compressionTriggerLength)
        }
        if (binding.settingsContent.editSearxngUrl.text?.toString() != settings.searxngUrl) {
            binding.settingsContent.editSearxngUrl.setText(settings.searxngUrl)
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
        val view = binding.settingsContent.cardPermission
        val titleColor = if (granted) {
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)
        } else {
            androidx.core.content.ContextCompat.getColor(this, R.color.mote_error)
        }
        binding.settingsContent.textPermissionTitle.setTextColor(titleColor)
        binding.settingsContent.textPermissionDescription.setTextColor(
            MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant)
        )
        binding.settingsContent.iconPermission.setColorFilter(titleColor)
        binding.settingsContent.textPermissionDescription.text = getString(
            if (granted) R.string.settings_permission_granted else R.string.settings_permission_denied
        )
        binding.settingsContent.buttonOpenPermissionSettings.text = getString(
            if (granted) R.string.settings_permission_reopen else R.string.settings_permission_open
        )
    }

    private fun parseLengthInput(rawValue: String?, onError: (String?) -> Unit): Int? {
        val value = rawValue.orEmpty().trim()
        if (value.isBlank()) {
            onError(null)
            return 0
        }

        val number = value.toLongOrNull()
        if (number == null || number !in 0..Int.MAX_VALUE.toLong()) {
            onError(getString(R.string.settings_number_invalid))
            return null
        }

        onError(null)
        return number.toInt()
    }
}
