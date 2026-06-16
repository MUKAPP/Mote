package com.mukapp.mote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.mukapp.mote.data.ApiSettingsStore
import com.mukapp.mote.data.model.ModelInfo
import com.mukapp.mote.data.model.ModelProvider
import com.mukapp.mote.data.model.ProviderType
import com.mukapp.mote.data.model.ReasoningEffortOptions
import com.mukapp.mote.databinding.ActivityProviderEditorBinding
import com.mukapp.mote.databinding.DialogEditModelBinding
import com.mukapp.mote.network.ChatApiClient
import com.mukapp.mote.ui.ProviderModelAdapter
import com.mukapp.mote.util.MoteLog
import com.mukapp.mote.util.dpInt
import kotlinx.coroutines.launch

class ProviderEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityProviderEditorBinding
    private lateinit var modelAdapter: ProviderModelAdapter

    private var providerId: String = ""
    private val models = mutableListOf<ModelInfo>()
    private var isExistingProvider = false
    private var isFetching = false
    private var selectedProviderType: ProviderType = ProviderType.Generic

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityProviderEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val incoming = intent.getStringExtra(EXTRA_PROVIDER)?.let { ApiSettingsStore.providerFromJson(it) }
        isExistingProvider = incoming != null
        val provider = incoming ?: ModelProvider()
        providerId = provider.id

        setupChrome()
        setupInsets()
        setupModels(provider)
        setupActions()
        populate(provider)
    }

    private fun setupChrome() {
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { handleBack() }
        val fallbackSurfaceColor = ContextCompat.getColor(this, R.color.mote_background)
        val frameClearDrawable = window.decorView.background ?: fallbackSurfaceColor.toDrawable()
        val blurBaseColor = ContextCompat.getColor(this, R.color.mote_background)
        val overlayColor = ColorUtils.setAlphaComponent(blurBaseColor, (255 * 0.6f).toInt())
        binding.blurViewToolbar.setupWith(binding.blurTarget)
            .setFrameClearDrawable(frameClearDrawable)
            .setBlurRadius(20f)
            .setOverlayColor(overlayColor)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBack()
            }
        })
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val toolbarHeight = binding.toolbar.minimumHeight.takeIf { it > 0 } ?: 56.dpInt
            binding.blurViewToolbar.updatePadding(top = systemBars.top)
            binding.providerContent.updatePadding(
                top = systemBars.top + toolbarHeight + 16.dpInt,
                bottom = systemBars.bottom
            )
            insets
        }
    }

    private fun setupModels(provider: ModelProvider) {
        selectedProviderType = provider.type
        models.clear()
        models.addAll(provider.models)
        modelAdapter = ProviderModelAdapter(
            providerType = { selectedProviderType },
            onEdit = { index, model -> showModelDialog(index, model) },
            onDelete = { index, model -> confirmDeleteModel(index, model) }
        )
        binding.recyclerProviderModels.adapter = modelAdapter
        binding.recyclerProviderModels.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerProviderModels.itemAnimator = null
        refreshModels()
    }

    private fun setupActions() {
        binding.buttonFetchModels.setOnClickListener { fetchModels() }
        binding.buttonAddModel.setOnClickListener { showModelDialog(null, null) }
        binding.buttonSaveProvider.setOnClickListener { saveProvider() }
        binding.buttonDeleteProvider.isVisible = isExistingProvider
        binding.buttonDeleteProvider.setOnClickListener { confirmDeleteProvider() }
    }

    private fun populate(provider: ModelProvider) {
        binding.editProviderName.setText(provider.name)
        binding.editProviderBaseUrl.setText(provider.baseUrl)
        binding.editProviderApiKey.setText(provider.apiKey)
        binding.toggleProviderType.check(chipIdForType(provider.type))
        binding.toggleProviderType.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newType = typeForChipId(checkedId)
            if (newType != selectedProviderType) {
                selectedProviderType = newType
                // 思考档标签随类型变化，重新渲染模型副标题。
                refreshModels()
            }
        }
    }

    private fun chipIdForType(type: ProviderType): Int = when (type) {
        ProviderType.Generic -> R.id.chip_type_generic
        ProviderType.DeepSeek -> R.id.chip_type_deepseek
        ProviderType.Gemini -> R.id.chip_type_gemini
        ProviderType.Qwen -> R.id.chip_type_qwen
        ProviderType.Claude -> R.id.chip_type_claude
    }

    private fun typeForChipId(id: Int): ProviderType = when (id) {
        R.id.chip_type_deepseek -> ProviderType.DeepSeek
        R.id.chip_type_gemini -> ProviderType.Gemini
        R.id.chip_type_qwen -> ProviderType.Qwen
        R.id.chip_type_claude -> ProviderType.Claude
        else -> ProviderType.Generic
    }

    private fun refreshModels() {
        modelAdapter.submit(models.toList())
        binding.textModelsEmpty.isVisible = models.isEmpty()
    }

    private fun fetchModels() {
        if (isFetching) return
        val baseUrl = binding.editProviderBaseUrl.text?.toString().orEmpty().trim()
        if (baseUrl.isBlank() || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            binding.inputProviderBaseUrl.error = getString(R.string.provider_editor_base_url_required)
            return
        }
        binding.inputProviderBaseUrl.error = null
        val apiKey = binding.editProviderApiKey.text?.toString().orEmpty().trim()
        isFetching = true
        binding.buttonFetchModels.isEnabled = false
        binding.buttonFetchModels.text = getString(R.string.provider_editor_fetching)
        lifecycleScope.launch {
            val result = runCatching { ChatApiClient.listModels(baseUrl, apiKey) }
            isFetching = false
            binding.buttonFetchModels.isEnabled = true
            binding.buttonFetchModels.text = getString(R.string.provider_editor_fetch_models)
            result.onSuccess { fetched ->
                val existingIds = models.map { it.id }.toHashSet()
                var added = 0
                fetched.forEach { model ->
                    if (existingIds.add(model.id)) {
                        models.add(
                            model.copy(
                                reasoningEffort = ReasoningEffortOptions.defaultKeyFor(selectedProviderType)
                            )
                        )
                        added += 1
                    }
                }
                refreshModels()
                Snackbar.make(
                    binding.root,
                    getString(R.string.provider_editor_fetch_success, added),
                    Snackbar.LENGTH_SHORT
                ).show()
            }.onFailure { error ->
                MoteLog.e("Settings", "拉取模型列表失败", error)
                Snackbar.make(
                    binding.root,
                    getString(R.string.provider_editor_fetch_failed, error.message.orEmpty()),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showModelDialog(index: Int?, existing: ModelInfo?) {
        val dialogBinding = DialogEditModelBinding.inflate(layoutInflater)
        dialogBinding.editModelId.setText(existing?.id.orEmpty())
        dialogBinding.editModelDisplayName.setText(existing?.displayName.orEmpty())
        dialogBinding.editModelContextLength.setText(
            existing?.contextLength?.takeIf { it > 0 }?.toString().orEmpty()
        )

        // 思考强度档位随提供商类型变化，运行时按当前类型动态构建。
        val effortChipGroup = dialogBinding.toggleModelReasoning
        val initialKey = ReasoningEffortOptions.normalizeKey(selectedProviderType, existing?.reasoningEffort)
        effortChipGroup.removeAllViews()
        var initialChipId = View.NO_ID
        ReasoningEffortOptions.optionsFor(selectedProviderType).forEach { option ->
            val chip = Chip(this, null, com.google.android.material.R.attr.chipStyle).apply {
                setChipDrawable(
                    ChipDrawable.createFromAttributes(
                        this@ProviderEditorActivity, null, 0, R.style.Widget_Mote_Chip_Choice
                    )
                )
                id = View.generateViewId()
                text = getString(option.labelRes)
                tag = option.key
                isCheckable = true
            }
            effortChipGroup.addView(chip)
            if (option.key == initialKey) {
                initialChipId = chip.id
            }
        }
        if (initialChipId != View.NO_ID) {
            effortChipGroup.check(initialChipId)
        }

        val titleRes = if (index == null) R.string.model_dialog_add_title else R.string.model_dialog_edit_title
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(dialogBinding.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val id = dialogBinding.editModelId.text?.toString().orEmpty().trim()
                if (id.isBlank()) {
                    dialogBinding.inputModelId.error = getString(R.string.model_dialog_id_required)
                    return@setOnClickListener
                }
                val reasoning = effortChipGroup.checkedChipId
                    .takeIf { it != View.NO_ID }
                    ?.let { effortChipGroup.findViewById<Chip>(it)?.tag as? String }
                    ?: ReasoningEffortOptions.defaultKeyFor(selectedProviderType)
                val contextLength = dialogBinding.editModelContextLength.text?.toString()
                    ?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val newModel = ModelInfo(
                    id = id,
                    displayName = dialogBinding.editModelDisplayName.text?.toString().orEmpty().trim(),
                    contextLength = contextLength,
                    reasoningEffort = reasoning
                )
                if (index == null) {
                    val duplicateIndex = models.indexOfFirst { it.id == id }
                    if (duplicateIndex >= 0) {
                        models[duplicateIndex] = newModel
                    } else {
                        models.add(newModel)
                    }
                } else {
                    models[index] = newModel
                }
                refreshModels()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDeleteModel(index: Int, model: ModelInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.provider_editor_delete_model_title)
            .setMessage(getString(R.string.provider_editor_delete_model_message, model.label))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                if (index in models.indices) {
                    models.removeAt(index)
                    refreshModels()
                }
            }
            .show()
    }

    private fun buildCurrentProvider(): ModelProvider {
        return ModelProvider(
            id = providerId,
            name = binding.editProviderName.text?.toString().orEmpty().trim(),
            baseUrl = binding.editProviderBaseUrl.text?.toString().orEmpty().trim(),
            apiKey = binding.editProviderApiKey.text?.toString().orEmpty().trim(),
            type = selectedProviderType,
            models = models.toList()
        )
    }

    private fun saveProvider() {
        val name = binding.editProviderName.text?.toString().orEmpty().trim()
        val baseUrl = binding.editProviderBaseUrl.text?.toString().orEmpty().trim()
        var valid = true
        if (name.isBlank()) {
            binding.inputProviderName.error = getString(R.string.provider_editor_name_required)
            valid = false
        } else {
            binding.inputProviderName.error = null
        }
        if (baseUrl.isBlank() || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            binding.inputProviderBaseUrl.error = getString(R.string.provider_editor_base_url_required)
            valid = false
        } else {
            binding.inputProviderBaseUrl.error = null
        }
        if (!valid) return

        val resultIntent = Intent().apply {
            putExtra(EXTRA_PROVIDER, ApiSettingsStore.providerToJson(buildCurrentProvider()))
            putExtra(EXTRA_PROVIDER_ID, providerId)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun confirmDeleteProvider() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.provider_editor_delete)
            .setMessage(getString(R.string.provider_editor_delete_model_message, buildCurrentProvider().label))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val resultIntent = Intent().apply {
                    putExtra(EXTRA_DELETED, true)
                    putExtra(EXTRA_PROVIDER_ID, providerId)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            }
            .show()
    }

    private fun hasUnsavedChanges(): Boolean {
        val original = intent.getStringExtra(EXTRA_PROVIDER)?.let { ApiSettingsStore.providerFromJson(it) }
            ?: ModelProvider(id = providerId)
        return buildCurrentProvider() != original
    }

    private fun handleBack() {
        if (!hasUnsavedChanges()) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.provider_editor_discard_title)
            .setMessage(R.string.provider_editor_discard_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> finish() }
            .show()
    }

    companion object {
        const val EXTRA_PROVIDER = "extra_provider"
        const val EXTRA_PROVIDER_ID = "extra_provider_id"
        const val EXTRA_DELETED = "extra_deleted"

        fun newIntent(context: Context, providerJson: String?): Intent {
            return Intent(context, ProviderEditorActivity::class.java).apply {
                if (providerJson != null) {
                    putExtra(EXTRA_PROVIDER, providerJson)
                }
            }
        }
    }
}
