package com.mukapp.mote

import android.app.Activity
import android.os.Bundle
import android.text.format.Formatter
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
import androidx.lifecycle.lifecycleScope
import com.mukapp.mote.data.ApiSettingsStore
import com.mukapp.mote.data.ChatHistoryStore
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ModelRef
import com.mukapp.mote.data.model.ReasoningEffortOptions
import com.mukapp.mote.data.model.SearchProvider
import com.mukapp.mote.data.model.findProvider
import com.mukapp.mote.data.model.normalizingRoleRefs
import com.mukapp.mote.data.model.resolvedSearchProvider
import com.mukapp.mote.databinding.ActivitySettingsBinding
import com.mukapp.mote.ui.ModelPickerBottomSheet
import com.mukapp.mote.ui.ProviderAdapter
import com.mukapp.mote.util.dpInt
import com.mukapp.mote.util.hasManageAllFilesPermission
import com.mukapp.mote.util.openManageAllFilesAccessSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var providerAdapter: ProviderAdapter

    private var workingSettings: ApiSettings = ApiSettings()
    private var isApplyingSettings: Boolean = false
    private var storageUsageGeneration = 0L

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
        // 编辑结果已由编辑页写入存储（密钥不经 Intent 回传），重新加载刷新界面。
        if (providerId.isNotBlank()) {
            workingSettings = ApiSettingsStore.load(this)
            applySettings(workingSettings)
        }
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
        binding.settingsContent.buttonRefreshStorageUsage.setOnClickListener { refreshStorageUsage() }
        applySettings(workingSettings)
        refreshPermissionState()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        refreshStorageUsage()
    }

    private fun refreshStorageUsage() {
        val generation = ++storageUsageGeneration
        binding.settingsContent.buttonRefreshStorageUsage.isEnabled = false
        lifecycleScope.launch {
            val usage = withContext(Dispatchers.IO) { ChatHistoryStore.getStorageUsage(applicationContext) }
            if (generation != storageUsageGeneration) return@launch
            binding.settingsContent.textHistoryStorageUsage.text = getString(
                R.string.settings_storage_usage,
                Formatter.formatFileSize(this@SettingsActivity, usage.totalBytes),
                Formatter.formatFileSize(this@SettingsActivity, usage.attachmentBytes)
            ) + if (usage.complete) "" else "\n" + getString(R.string.settings_storage_usage_incomplete)
            binding.settingsContent.buttonRefreshStorageUsage.isEnabled = true
        }
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
                    ProviderEditorActivity.newIntent(this, provider.id)
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
            if (isApplyingSettings) return@doAfterTextChanged
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
        val onToolPermissionsChanged = {
            if (!isApplyingSettings) {
                val updated = workingSettings.copy(
                    allowShell = binding.settingsContent.checkboxAllowShell.isChecked,
                    allowFileTools = binding.settingsContent.checkboxAllowFileTools.isChecked,
                    allowNetworkFetch = binding.settingsContent.checkboxAllowNetworkFetch.isChecked,
                    allowLocalNetwork = binding.settingsContent.checkboxAllowLocalNetwork.isChecked
                )
                if (updated != workingSettings) {
                    workingSettings = updated
                    ApiSettingsStore.save(this, workingSettings)
                }
            }
        }
        binding.settingsContent.checkboxAllowShell.setOnCheckedChangeListener { _, _ -> onToolPermissionsChanged() }
        binding.settingsContent.checkboxAllowFileTools.setOnCheckedChangeListener { _, _ -> onToolPermissionsChanged() }
        binding.settingsContent.checkboxAllowNetworkFetch.setOnCheckedChangeListener { _, _ -> onToolPermissionsChanged() }
        binding.settingsContent.checkboxAllowLocalNetwork.setOnCheckedChangeListener { _, _ -> onToolPermissionsChanged() }
        binding.settingsContent.editAttachmentStorageLimit.doAfterTextChanged { text ->
            if (isApplyingSettings) return@doAfterTextChanged
            val raw = text?.toString().orEmpty().trim()
            val limit = if (raw.isEmpty() || raw == "0") {
                0
            } else {
                raw.toIntOrNull()?.takeIf { it > 0 }
            }
            if (limit == null) {
                binding.settingsContent.inputAttachmentStorageLimit.error =
                    getString(R.string.nonnegative_integer_required)
                return@doAfterTextChanged
            }
            binding.settingsContent.inputAttachmentStorageLimit.error = null
            if (limit != workingSettings.attachmentStorageLimitMiB) {
                workingSettings = workingSettings.copy(attachmentStorageLimitMiB = limit)
                ApiSettingsStore.save(this, workingSettings)
            }
        }
        val onSearchChanged = {
            if (!isApplyingSettings) {
                val searxng = binding.settingsContent.editSearxngUrl.text?.toString().orEmpty().trim()
                val tavily = binding.settingsContent.editTavilyApiKey.text?.toString().orEmpty().trim()
                val anysearch = binding.settingsContent.editAnysearchApiKey.text?.toString().orEmpty().trim()
                val configuredProviders = buildList {
                    if (searxng.isNotBlank()) add(SearchProvider.Searxng)
                    if (tavily.isNotBlank()) add(SearchProvider.Tavily)
                    if (anysearch.isNotBlank()) add(SearchProvider.Anysearch)
                }
                val selectedProvider = workingSettings.searchProvider
                    ?: configuredProviders.singleOrNull()
                val updated = workingSettings.copy(
                    searchProvider = selectedProvider,
                    searxngUrl = searxng,
                    tavilyApiKey = tavily,
                    anysearchApiKey = anysearch
                )
                if (updated != workingSettings) {
                    workingSettings = updated
                    ApiSettingsStore.save(this, workingSettings)
                }
                selectedProvider?.let { provider ->
                    val buttonId = searchProviderButtonId(provider)
                    if (binding.settingsContent.radioSearchProvider.checkedRadioButtonId != buttonId) {
                        binding.settingsContent.radioSearchProvider.check(buttonId)
                    }
                }
            }
        }
        binding.settingsContent.editSearxngUrl.doAfterTextChanged { onSearchChanged() }
        binding.settingsContent.editTavilyApiKey.doAfterTextChanged { onSearchChanged() }
        binding.settingsContent.editAnysearchApiKey.doAfterTextChanged { onSearchChanged() }
        binding.settingsContent.radioSearchProvider.setOnCheckedChangeListener { _, checkedId ->
            if (isApplyingSettings) return@setOnCheckedChangeListener
            val provider = when (checkedId) {
                R.id.radio_search_searxng -> SearchProvider.Searxng
                R.id.radio_search_tavily -> SearchProvider.Tavily
                R.id.radio_search_anysearch -> SearchProvider.Anysearch
                else -> null
            }
            if (provider != workingSettings.searchProvider) {
                workingSettings = workingSettings.copy(searchProvider = provider)
                ApiSettingsStore.save(this, workingSettings)
            }
        }
    }

    private fun searchProviderButtonId(provider: SearchProvider): Int {
        return when (provider) {
            SearchProvider.Searxng -> R.id.radio_search_searxng
            SearchProvider.Tavily -> R.id.radio_search_tavily
            SearchProvider.Anysearch -> R.id.radio_search_anysearch
        }
    }

    private fun showModelPicker(selected: ModelRef?, onSelected: (ModelRef) -> Unit) {
        ModelPickerBottomSheet.show(this, workingSettings, selected, onSelected)
    }

    private fun removeProvider(providerId: String) {
        if (providerId.isBlank()) return
        val providers = workingSettings.providers.filterNot { it.id == providerId }
        workingSettings = workingSettings.copy(providers = providers).normalizingRoleRefs()
        persist()
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

        isApplyingSettings = true
        try {
            val percent = settings.compressionTriggerPercent.coerceIn(0, 100).toString()
            if (binding.settingsContent.editCompressionTriggerPercent.text?.toString() != percent) {
                binding.settingsContent.editCompressionTriggerPercent.setText(percent)
            }
            binding.settingsContent.checkboxAllowShell.isChecked = settings.allowShell
            binding.settingsContent.checkboxAllowFileTools.isChecked = settings.allowFileTools
            binding.settingsContent.checkboxAllowNetworkFetch.isChecked = settings.allowNetworkFetch
            binding.settingsContent.checkboxAllowLocalNetwork.isChecked = settings.allowLocalNetwork
            val attachmentLimit = settings.attachmentStorageLimitMiB.coerceAtLeast(0).toString()
            if (binding.settingsContent.editAttachmentStorageLimit.text?.toString() != attachmentLimit) {
                binding.settingsContent.editAttachmentStorageLimit.setText(attachmentLimit)
            }
            binding.settingsContent.inputAttachmentStorageLimit.error = null
            if (binding.settingsContent.editSearxngUrl.text?.toString() != settings.searxngUrl) {
                binding.settingsContent.editSearxngUrl.setText(settings.searxngUrl)
            }
            if (binding.settingsContent.editTavilyApiKey.text?.toString() != settings.tavilyApiKey) {
                binding.settingsContent.editTavilyApiKey.setText(settings.tavilyApiKey)
            }
            if (binding.settingsContent.editAnysearchApiKey.text?.toString() != settings.anysearchApiKey) {
                binding.settingsContent.editAnysearchApiKey.setText(settings.anysearchApiKey)
            }
            val selectedProvider = settings.searchProvider ?: settings.resolvedSearchProvider()
            if (selectedProvider == null) {
                binding.settingsContent.radioSearchProvider.clearCheck()
            } else {
                binding.settingsContent.radioSearchProvider.check(searchProviderButtonId(selectedProvider))
            }
        } finally {
            isApplyingSettings = false
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
        val mapping = ReasoningEffortOptions.normalizeMapping(provider.type, model.reasoningEfforts)
        val effortKey = ref.reasoningEffort?.let {
            ReasoningEffortOptions.normalizeKey(provider.type, it, mapping)
        } ?: ReasoningEffortOptions.normalizeKey(provider.type, null, mapping)
        return getString(
            R.string.settings_role_model_with_effort,
            provider.label,
            model.label,
            effortKey
        )
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
