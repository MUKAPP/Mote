package com.mukapp.mote.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.mukapp.mote.data.model.ModelInfo
import com.mukapp.mote.data.model.ProviderType
import com.mukapp.mote.databinding.ItemProviderModelBinding

class ProviderModelAdapter(
    private val providerType: () -> ProviderType,
    private val onEdit: ((Int, ModelInfo) -> Unit)? = null,
    private val onDelete: ((Int, ModelInfo) -> Unit)? = null,
    private val onSelect: ((Int, ModelInfo) -> Unit)? = null
): RecyclerView.Adapter<ProviderModelAdapter.ViewHolder>() {

    private val items = mutableListOf<ModelInfo>()

    fun submit(models: List<ModelInfo>) {
        items.clear()
        items.addAll(models)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProviderModelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemProviderModelBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.rowModel.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    (onSelect ?: onEdit)?.invoke(position, items[position])
                }
            }
            binding.buttonDeleteModel.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onDelete?.invoke(position, items[position])
                }
            }
        }

        fun bind(model: ModelInfo) {
            binding.textModelName.text = model.label
            binding.textModelSubtitle.text =
                ModelPickerBottomSheet.modelSubtitle(binding.root.context, model, providerType())
            binding.buttonDeleteModel.isVisible = onDelete != null
        }
    }
}
