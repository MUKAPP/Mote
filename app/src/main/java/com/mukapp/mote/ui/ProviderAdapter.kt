package com.mukapp.mote.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mukapp.mote.R
import com.mukapp.mote.data.model.ModelProvider
import com.mukapp.mote.databinding.ItemProviderBinding

class ProviderAdapter(
    private val onClick: (ModelProvider) -> Unit,
    private val onDelete: (ModelProvider) -> Unit
) : RecyclerView.Adapter<ProviderAdapter.ViewHolder>() {

    private val items = mutableListOf<ModelProvider>()

    fun submit(providers: List<ModelProvider>) {
        items.clear()
        items.addAll(providers)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemProviderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemProviderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.cardProvider.setOnClickListener {
                currentItem()?.let(onClick)
            }
            binding.buttonDeleteProvider.setOnClickListener {
                currentItem()?.let(onDelete)
            }
        }

        fun bind(provider: ModelProvider) {
            val context = binding.root.context
            binding.textProviderName.text = provider.label
            binding.textProviderBaseUrl.text = provider.baseUrl
            binding.textProviderModels.text =
                context.getString(R.string.settings_provider_models_count, provider.models.size)
        }

        private fun currentItem(): ModelProvider? {
            val position = bindingAdapterPosition
            return if (position != RecyclerView.NO_POSITION && position in items.indices) {
                items[position]
            } else {
                null
            }
        }
    }
}
