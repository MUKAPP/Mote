package com.mukapp.mote

import android.app.Activity
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.mukapp.mote.data.ApiSettingsStore
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ModelProvider
import com.mukapp.mote.data.model.ModelRef
import com.mukapp.mote.data.model.findProvider
import com.mukapp.mote.databinding.ActivitySettingsBinding
import com.mukapp.mote.ui.ModelPickerBottomSheet
import com.mukapp.mote.ui.ProviderAdapter
import com.mukapp.mote.util.dpInt
import com.mukapp.mote.util.hasManageAllFilesPermission
import com.mukapp.mote.util.openManageAllFilesAccessSettings

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var providerAdapter: ProviderAdapter

    private var workingSettings: ApiSettings = ApiSettings()

    private val providerEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val providerId = data.getStringExtra(ProviderEditorActivity.EXTRA_PROVIDER_ID).orEmpty()
        if (data.getBooleanExtra(ProviderEditorActivity.EXTRA_DELETED, false)) {
            removeProvider(providerId)
            return@registerForActivityResult
        }
        val json = data.getStringExtra(ProviderEditorActivity.EXTRA_PROVIDER) ?: return@registerForActivityResult
        ApiSettingsStore.providerFromJson(json)?.let { applyProvider(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        workingSettings = ApiSettingsStore.load(this)

        setupChrome()
        setupInsets()
        setupProviders()
        setupRoles()
        setupScalarFields()
        applySettings(workingSettings)
        refreshPermissionState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
    }

    private fun setupChrome() {
        binding.toolbar.setTitle(R.string.title_settings)
        binding.toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        val fallbackSurfaceColor = ContextCompat.getColor(this, R.color.mote_background)
        val frameClearDrawable = window.decorView.background ?: fallbackSurfaceColor.toDrawable()
        val blurBaseColor = ContextCompat.getColor(this, R.color.mote_background)
        val overlayColor = ColorUtils.setAlphaComponent(blurBaseColor, (255 * 0.6f).toInt())
        binding.blurViewToolbar.setupWith(binding.blurTarget)
            .setFrameClearDrawable(frameClearDrawable)
            .setBlurRadius(20f)
            .setOverlayColor(overlayColor)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val toolbarHeight = binding.toolbar.minimumHeight.takeIf { it > 0 } ?: 56.dpInt
            binding.blurViewToolbar.updatePadding(top = systemBars.top)
            binding.settingsContent.root.updatePadding(
                top = systemBars.top + toolbarHeight + 16.dpInt,
                bottom = systemBars.bottom
            )
            insets
        }
    }

    private fun setupProviders() {
        providerAdapter = ProviderAdapter(
            onClick = { provider ->
                providerEditorLauncher.launch(
                    ProviderEditorActivity.newIntent(this, ApiSettingsStore.providerToJson(provider))
                )
            },
            onDelete = { provider -> removeProvider(provider.id) }
        )
        binding.settingsContent.recyclerProviders.adapter = providerAdapter
        binding.settingsContent.recyclerProviders.layoutManager = LinearLayoutManager(this)
        binding.settingsContent.recyclerProviders.itemAnimator = null
        binding.settingsContent.buttonAddProvider.setOnClickListener {
            providerEditorLauncher.launch(ProviderEditorActivity.newIntent(this, null))
        }
    }

    private fun setupRoles() {
        binding.settingsContent.rowRoleChat.setOnClickListener {
            showModelPicker(workingSettings.chatModel) { ref ->
                workingSettings = workingSettings.copy(chatModel = ref)
                persist()
            }
        }
        binding.settingsContent.rowRoleTitle.setOnClickListener {
            showModelPicker(workingSettings.titleModel) { ref ->
                workingSettings = workingSettings.copy(titleModel = ref)
                persist()
            }
        }
        binding.settingsContent.rowRoleCompression.setOnClickListener {
            showModelPicker(workingSettings.compressionModel) { ref ->
                workingSettings = workingSettings.copy(compressionModel = ref)
                persist()
            }
        }
        binding.settingsContent.buttonClearTitle.setOnClickListener {
            workingSettings = workingSettings.copy(titleModel = null)
            persist()
        }
        binding.settingsContent.buttonClearCompression.setOnClickListener {
            workingSettings = workingSettings.copy(compressionModel = null)
            persist()
        }
    }

    private fun setupScalarFields() {
        binding.settingsContent.editCompressionTriggerPercent.doAfterTextChanged { text ->
            val raw = text?.toString().orEmpty().trim()
            val percent = if (raw.isBlank()) {
                ApiSettings.DefaultCompressionTriggerPercent
            } else {
                raw.toIntOrNull()
            }
            if (percent == null || percent !in 0..100) {
                binding.settingsContent.inputCompressionTriggerPercent.error =
                    getString(R.string.settings_compression_percent_invalid)
                return@doAfterTextChanged
            }
            binding.settingsContent.inputCompressionTriggerPercent.error = null
            if (percent != workingSettings.compressionTriggerPercent) {
                workingSettings = workingSettings.copy(compressionTriggerPercent = percent)
                ApiSettingsStore.save(this, workingSettings)
            }
        }
        val onSearchChanged = {
            val searxng = binding.settingsContent.editSearxngUrl.text?.toString().orEmpty().trim()
            val tavily = binding.settingsContent.editTavilyApiKey.text?.toString().orEmpty().trim()
            if (searxng.isNotBlank() && tavily.isNotBlank()) {
                val error = getString(R.string.settings_search_provider_conflict)
                binding.settingsContent.inputSearxngUrl.error = error
                binding.settingsContent.inputTavilyApiKey.error = error
            } else {
                binding.settingsContent.inputSearxngUrl.error = null
                binding.settingsContent.inputTavilyApiKey.error = null
                if (searxng != workingSettings.searxngUrl || tavily != workingSettings.tavilyApiKey) {
                    workingSettings = workingSettings.copy(searxngUrl = searxng, tavilyApiKey = tavily)
                    ApiSettingsStore.save(this, workingSettings)
                }
            }
        }
        binding.settingsContent.editSearxngUrl.doAfterTextChanged { onSearchChanged() }
        binding.settingsContent.editTavilyApiKey.doAfterTextChanged { onSearchChanged() }
    }

    private fun showModelPicker(selected: ModelRef?, onSelected: (ModelRef) -> Unit) {
        ModelPickerBottomSheet.show(this, workingSettings, selected, onSelected)
    }

    /** 应用提供商编辑结果：替换或新增，并在缺省时自动选择对话模型。 */
    private fun applyProvider(provider: ModelProvider) {
        val providers = workingSettings.providers.toMutableList()
        val index = providers.indexOfFirst { it.id == provider.id }
        if (index >= 0) {
            providers[index] = provider
        } else {
            providers.add(provider)
        }
        var updated = workingSettings.copy(providers = providers)
        // 引用的模型若已被删除则清空对应用途。
        updated = updated.copy(
            chatModel = updated.chatModel?.takeIf { refExists(updated, it) },
            titleModel = updated.titleModel?.takeIf { refExists(updated, it) },
            compressionModel = updated.compressionModel?.takeIf { refExists(updated, it) }
        )
        // 尚未选择对话模型且该提供商有模型时，默认选第一个。
        if (updated.chatModel == null) {
            provider.models.firstOrNull()?.let { model ->
                updated = updated.copy(chatModel = ModelRef(provider.id, model.id))
            }
        }
        workingSettings = updated
        persist()
    }

    private fun removeProvider(providerId: String) {
        if (providerId.isBlank()) return
        val providers = workingSettings.providers.filterNot { it.id == providerId }
        var updated = workingSettings.copy(providers = providers)
        updated = updated.copy(
            chatModel = updated.chatModel?.takeIf { refExists(updated, it) },
            titleModel = updated.titleModel?.takeIf { refExists(updated, it) },
            compressionModel = updated.compressionModel?.takeIf { refExists(updated, it) }
        )
        workingSettings = updated
        persist()
    }

    private fun refExists(settings: ApiSettings, ref: ModelRef): Boolean {
        return settings.findProvider(ref.providerId)?.models?.any { it.id == ref.modelId } == true
    }

    private fun persist() {
        ApiSettingsStore.save(this, workingSettings)
        providerAdapter.submit(workingSettings.providers)
        binding.settingsContent.textProvidersEmpty.isVisible = workingSettings.providers.isEmpty()
        refreshRoleLabels()
    }

    private fun applySettings(settings: ApiSettings) {
        providerAdapter.submit(settings.providers)
        binding.settingsContent.textProvidersEmpty.isVisible = settings.providers.isEmpty()
        refreshRoleLabels()

        val percent = settings.compressionTriggerPercent.coerceIn(0, 100).toString()
        if (binding.settingsContent.editCompressionTriggerPercent.text?.toString() != percent) {
            binding.settingsContent.editCompressionTriggerPercent.setText(percent)
        }
        if (binding.settingsContent.editSearxngUrl.text?.toString() != settings.searxngUrl) {
            binding.settingsContent.editSearxngUrl.setText(settings.searxngUrl)
        }
        if (binding.settingsContent.editTavilyApiKey.text?.toString() != settings.tavilyApiKey) {
            binding.settingsContent.editTavilyApiKey.setText(settings.tavilyApiKey)
        }
    }

    private fun refreshRoleLabels() {
        binding.settingsContent.textRoleChatValue.text = labelForRef(workingSettings.chatModel)
        binding.settingsContent.textRoleTitleValue.text = labelForRef(workingSettings.titleModel)
        binding.settingsContent.textRoleCompressionValue.text = labelForRef(workingSettings.compressionModel)
        binding.settingsContent.buttonClearTitle.isVisible = workingSettings.titleModel != null
        binding.settingsContent.buttonClearCompression.isVisible = workingSettings.compressionModel != null
    }

    private fun labelForRef(ref: ModelRef?): String {
        ref ?: return getString(R.string.settings_role_unset)
        val provider = workingSettings.findProvider(ref.providerId)
            ?: return getString(R.string.settings_role_unset)
        val model = provider.models.firstOrNull { it.id == ref.modelId }
            ?: return getString(R.string.settings_role_unset)
        return "${provider.label} · ${model.label}"
    }

    private fun refreshPermissionState() {
        val granted = hasManageAllFilesPermission()
        val titleColor = if (granted) {
            ContextCompat.getColor(this, R.color.mote_on_background)
        } else {
            ContextCompat.getColor(this, R.color.mote_error)
        }
        binding.settingsContent.textPermissionTitle.setTextColor(titleColor)
        binding.settingsContent.textPermissionDescription.setTextColor(
            ContextCompat.getColor(this, R.color.mote_on_background_secondary)
        )
        binding.settingsContent.iconPermission.setColorFilter(titleColor)
        binding.settingsContent.textPermissionDescription.text = getString(
            if (granted) R.string.settings_permission_granted else R.string.settings_permission_denied
        )
        binding.settingsContent.buttonOpenPermissionSettings.text = getString(
            if (granted) R.string.settings_permission_reopen else R.string.settings_permission_open
        )
        binding.settingsContent.buttonOpenPermissionSettings.setOnClickListener {
            openManageAllFilesAccessSettings(this)
        }
    }
}
