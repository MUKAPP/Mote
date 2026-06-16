package com.mukapp.mote.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.mukapp.mote.R
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ModelInfo
import com.mukapp.mote.data.model.ModelRef
import com.mukapp.mote.data.model.ProviderType
import com.mukapp.mote.data.model.ReasoningEffortOptions
import com.mukapp.mote.data.model.findProvider
import com.mukapp.mote.databinding.BottomSheetModelPickerBinding
import com.mukapp.mote.databinding.ItemModelPickerHeaderBinding
import com.mukapp.mote.databinding.ItemModelPickerModelBinding

/** 按提供商分组展示所有模型的底部选择面板，供首页切换与设置页用途选择复用。 */
object ModelPickerBottomSheet {

    fun show(
        context: Context,
        settings: ApiSettings,
        selected: ModelRef?,
        onSelected: (ModelRef) -> Unit,
        currentEffortKey: String? = null,
        onReasoningSelected: ((String) -> Unit)? = null
    ) {
        val dialog = BottomSheetDialog(context)
        val binding = BottomSheetModelPickerBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        bindReasoningSection(binding, settings, selected, currentEffortKey, onReasoningSelected)

        val rows = buildRows(settings)
        if (rows.isEmpty()) {
            binding.textModelPickerEmpty.isVisible = true
            binding.recyclerModelPicker.isVisible = false
        } else {
            binding.textModelPickerEmpty.isVisible = false
            binding.recyclerModelPicker.isVisible = true
            binding.recyclerModelPicker.layoutManager = LinearLayoutManager(context)
            binding.recyclerModelPicker.adapter = Adapter(rows, selected) { ref ->
                onSelected(ref)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /** 顶部区块：对当前所选模型按其提供商类型展示思考强度档位，可临时切换（不关闭面板）。
     *  仅当提供 [onReasoningSelected] 时显示（首页对话模型选择用；设置页选标题/压缩模型时不显示）。 */
    private fun bindReasoningSection(
        binding: BottomSheetModelPickerBinding,
        settings: ApiSettings,
        selected: ModelRef?,
        currentEffortKey: String?,
        onReasoningSelected: ((String) -> Unit)?
    ) {
        val provider = settings.findProvider(selected?.providerId)
        val model = provider?.models?.firstOrNull { it.id == selected?.modelId }
        if (onReasoningSelected == null || provider == null || model == null) {
            binding.layoutCurrentReasoning.isVisible = false
            return
        }
        binding.layoutCurrentReasoning.isVisible = true

        val context = binding.root.context
        val chipGroup = binding.chipGroupReasoning
        chipGroup.setOnCheckedStateChangeListener(null)
        chipGroup.removeAllViews()

        val selectedKey = ReasoningEffortOptions.normalizeKey(
            provider.type,
            currentEffortKey ?: model.reasoningEffort
        )
        var selectedChipId = View.NO_ID
        ReasoningEffortOptions.optionsFor(provider.type).forEach { option ->
            val chip = Chip(context, null, com.google.android.material.R.attr.chipStyle).apply {
                setChipDrawable(
                    ChipDrawable.createFromAttributes(
                        context, null, 0, R.style.Widget_Mote_Chip_Choice
                    )
                )
                id = View.generateViewId()
                text = context.getString(option.labelRes)
                tag = option.key
                isCheckable = true
            }
            chipGroup.addView(chip)
            if (option.key == selectedKey) {
                selectedChipId = chip.id
            }
        }
        if (selectedChipId != View.NO_ID) {
            chipGroup.check(selectedChipId)
        }
        chipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val key = group.findViewById<View>(checkedId)?.tag as? String
                ?: return@setOnCheckedStateChangeListener
            onReasoningSelected(key)
        }
    }

    private fun buildRows(settings: ApiSettings): List<Row> {
        return buildList {
            settings.providers.forEach { provider ->
                if (provider.models.isEmpty()) return@forEach
                add(Row.Header(provider.label))
                provider.models.forEach { model ->
                    add(Row.Model(provider.id, model, provider.type))
                }
            }
        }
    }

    private sealed interface Row {
        data class Header(val name: String) : Row
        data class Model(
            val providerId: String,
            val model: ModelInfo,
            val providerType: ProviderType
        ) : Row
    }

    private class Adapter(
        private val rows: List<Row>,
        private val selected: ModelRef?,
        private val onSelected: (ModelRef) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return if (rows[position] is Row.Header) TYPE_HEADER else TYPE_MODEL
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderHolder(ItemModelPickerHeaderBinding.inflate(inflater, parent, false))
            } else {
                ModelHolder(ItemModelPickerModelBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).bind(row)
                is Row.Model -> (holder as ModelHolder).bind(row)
            }
        }

        override fun getItemCount(): Int = rows.size

        private class HeaderHolder(
            private val binding: ItemModelPickerHeaderBinding
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.Header) {
                binding.textProviderHeader.text = row.name
            }
        }

        private inner class ModelHolder(
            private val binding: ItemModelPickerModelBinding
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.Model) {
                val context = binding.root.context
                binding.textModelName.text = row.model.label
                binding.textModelSubtitle.text = modelSubtitle(context, row.model, row.providerType)
                val isSelected = selected?.providerId == row.providerId &&
                    selected.modelId == row.model.id
                binding.iconModelSelected.isVisible = isSelected
                binding.root.setOnClickListener {
                    onSelected(ModelRef(row.providerId, row.model.id))
                }
            }
        }

        private companion object {
            const val TYPE_HEADER = 0
            const val TYPE_MODEL = 1
        }
    }

    internal fun modelSubtitle(context: Context, model: ModelInfo, providerType: ProviderType): String {
        val contextPart = if (model.contextLength > 0) {
            context.getString(R.string.model_context_length_set, model.contextLength)
        } else {
            context.getString(R.string.model_context_length_unset)
        }
        val reasoningLabel = context.getString(
            ReasoningEffortOptions.labelRes(providerType, model.reasoningEffort)
        )
        val reasoningPart = context.getString(R.string.model_reasoning_summary, reasoningLabel)
        return "$contextPart · $reasoningPart"
    }
}
