package com.mukapp.mote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
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
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
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
    private lateinit var fetchedModelAdapter: ProviderModelAdapter

    private var providerId: String = ""
    private val models = mutableListOf<ModelInfo>()
    private val fetchedModels = mutableListOf<ModelInfo>()
    private var isExistingProvider = false
    private var isFetching = false
    private var selectedProviderType: ProviderType = ProviderType.Generic

    private data class ReasoningEditorRow(
        val key: String,
        val enabled: MaterialCheckBox,
        val value: TextInputEditText
    )
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
        fetchedModelAdapter = ProviderModelAdapter(
            providerType = { selectedProviderType },
            onSelect = { _, model -> showFetchedModelDialog(model) }
        )
        binding.recyclerProviderModels.adapter = modelAdapter
        binding.recyclerProviderModels.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerProviderModels.itemAnimator = null
        binding.recyclerFetchedModels.adapter = fetchedModelAdapter
        binding.recyclerFetchedModels.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.recyclerFetchedModels.itemAnimator = null
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
        fetchedModelAdapter.submit(fetchedModels.toList())
        binding.textFetchedModelsEmpty.isVisible = fetchedModels.isEmpty()
        binding.recyclerFetchedModels.isVisible = fetchedModels.isNotEmpty()
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
        fetchedModels.clear()
        refreshModels()
        isFetching = true
        binding.buttonFetchModels.isEnabled = false
        binding.buttonFetchModels.text = getString(R.string.provider_editor_fetching)
        lifecycleScope.launch {
            val result = runCatching { ChatApiClient.listModels(baseUrl, apiKey) }
            isFetching = false
            binding.buttonFetchModels.isEnabled = true
            binding.buttonFetchModels.text = getString(R.string.provider_editor_fetch_models)
            result.onSuccess { fetched ->
                fetchedModels.clear()
                fetchedModels.addAll(
                    fetched.map { model ->
                        model.copy(
                            reasoningEfforts = ReasoningEffortOptions.defaultMappingFor(selectedProviderType)
                        )
                    }
                )
                refreshModels()
                Snackbar.make(
                    binding.root,
                    getString(R.string.provider_editor_fetch_success, fetchedModels.size),
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

    private fun showFetchedModelDialog(remoteModel: ModelInfo) {
        val existing = models.firstOrNull { it.id == remoteModel.id }
        val initial = if (existing == null) {
            remoteModel.copy(
                reasoningEfforts = ReasoningEffortOptions.defaultMappingFor(selectedProviderType)
            )
        } else {
            remoteModel.copy(
                displayName = existing.displayName,
                contextLength = remoteModel.contextLength.takeIf { it > 0 } ?: existing.contextLength,
                reasoningEfforts = existing.reasoningEfforts
            )
        }
        showModelDialog(index = null, existing = initial)
    }

    private fun buildReasoningEditorRows(
        dialogBinding: DialogEditModelBinding,
        existing: ModelInfo?
    ): List<ReasoningEditorRow> {
        val initialMapping = ReasoningEffortOptions.normalizeMapping(
            selectedProviderType,
            existing?.reasoningEfforts
        )
        val container = dialogBinding.layoutModelReasoningFields
        container.removeAllViews()
        return ReasoningEffortOptions.optionsFor(selectedProviderType).mapIndexed { index, option ->
            val row = layoutInflater.inflate(
                R.layout.item_reasoning_effort_editor,
                container,
                false
            )
            if (index > 0) {
                (row.layoutParams as LinearLayout.LayoutParams).topMargin = 4.dpInt
            }
            val enabled = row.findViewById<MaterialCheckBox>(R.id.checkbox_reasoning_enabled).apply {
                contentDescription = option.key
                isChecked = initialMapping.containsKey(option.key)
            }
            row.findViewById<TextView>(R.id.text_reasoning_key).text = option.key
            val value = row.findViewById<TextInputEditText>(R.id.edit_reasoning_value).apply {
                setText(
                    initialMapping[option.key]
                        ?: ReasoningEffortOptions.defaultValueFor(selectedProviderType, option.key)
                )
                isEnabled = enabled.isChecked
            }
            enabled.setOnCheckedChangeListener { _, checked -> value.isEnabled = checked }
            container.addView(row)
            ReasoningEditorRow(option.key, enabled, value)
        }
    }

    private fun showModelDialog(index: Int?, existing: ModelInfo?) {
        val dialogBinding = DialogEditModelBinding.inflate(layoutInflater)
        dialogBinding.editModelId.setText(existing?.id.orEmpty())
        dialogBinding.editModelDisplayName.setText(existing?.displayName.orEmpty())
        dialogBinding.editModelContextLength.setText(
            existing?.contextLength?.takeIf { it > 0 }?.toString().orEmpty()
        )
        val reasoningRows = buildReasoningEditorRows(dialogBinding, existing)
        dialogBinding.textModelReasoningError.isVisible = false

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
                dialogBinding.inputModelId.error = null

                val reasoningEfforts = linkedMapOf<String, String>()
                var hasInvalidValue = false
                reasoningRows.forEach { row ->
                    if (row.enabled.isChecked) {
                        val value = row.value.text?.toString().orEmpty().trim()
                        if (value.isBlank()) {
                            hasInvalidValue = true
                        } else {
                            reasoningEfforts[row.key] = value
                        }
                    }
                }
                if (reasoningEfforts.isEmpty() || hasInvalidValue) {
                    dialogBinding.textModelReasoningError.text = getString(
                        if (reasoningEfforts.isEmpty()) {
                            R.string.model_dialog_reasoning_required
                        } else {
                            R.string.model_dialog_reasoning_value_required
                        }
                    )
                    dialogBinding.textModelReasoningError.isVisible = true
                    return@setOnClickListener
                }
                dialogBinding.textModelReasoningError.isVisible = false

                val contextLength = dialogBinding.editModelContextLength.text?.toString()
                    ?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val newModel = ModelInfo(
                    id = id,
                    displayName = dialogBinding.editModelDisplayName.text?.toString().orEmpty().trim(),
                    contextLength = contextLength,
                    reasoningEfforts = ReasoningEffortOptions.normalizeMapping(
                        selectedProviderType,
                        reasoningEfforts
                    )
                )
                if (index != null && index in models.indices) {
                    models[index] = newModel
                } else {
                    val duplicateIndex = models.indexOfFirst { it.id == id }
                    if (duplicateIndex >= 0) {
                        models[duplicateIndex] = newModel
                    } else {
                        models.add(newModel)
                    }
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
