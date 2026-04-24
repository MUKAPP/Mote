package com.mukapp.mote.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.mukapp.mote.R
import com.mukapp.mote.data.model.ConversationSummary
import com.mukapp.mote.databinding.ItemConversationSummaryBinding

class ConversationSummaryAdapter(
    private val onConversationClick: (ConversationSummary) -> Unit
) : RecyclerView.Adapter<ConversationSummaryAdapter.ViewHolder>() {
    private val items = mutableListOf<ConversationSummary>()
    private var currentConversationId: String = ""

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return items[position].id.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversationSummaryBinding.inflate(
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

    fun submitItems(newItems: List<ConversationSummary>, selectedId: String) {
        val oldItems = items.toList()
        val oldSelectedId = currentConversationId
        currentConversationId = selectedId

        val sameIdentityOrder = oldItems.size == newItems.size && oldItems.indices.all { index ->
            oldItems[index].id == newItems[index].id
        }
        items.clear()
        items.addAll(newItems)

        if (sameIdentityOrder) {
            val changedIndices = newItems.indices.filter { index ->
                oldItems[index] != newItems[index] ||
                    oldItems[index].id == oldSelectedId ||
                    newItems[index].id == currentConversationId
            }
            when {
                changedIndices.isEmpty() -> Unit
                changedIndices.size <= 4 -> changedIndices.forEach { notifyItemChanged(it) }
                else -> notifyDataSetChanged()
            }
        } else {
            notifyDataSetChanged()
        }
    }

    inner class ViewHolder(
        private val binding: ItemConversationSummaryBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ConversationSummary) {
            val selected = item.id == currentConversationId
            val context = binding.root.context
            val backgroundAttr = if (selected) {
                com.google.android.material.R.attr.colorSecondaryContainer
            } else {
                com.google.android.material.R.attr.colorSurfaceContainerHighest
            }
            val textAttr = if (selected) {
                com.google.android.material.R.attr.colorOnSecondaryContainer
            } else {
                com.google.android.material.R.attr.colorOnSurface
            }
            binding.cardConversation.setCardBackgroundColor(
                MaterialColors.getColor(binding.cardConversation, backgroundAttr)
            )
            binding.textTitle.setTextColor(MaterialColors.getColor(binding.textTitle, textAttr))
            binding.textTitle.text = item.title.ifBlank { context.getString(R.string.nav_untitled_chat) }
            binding.root.isSelected = selected
            binding.root.setOnClickListener { onConversationClick(item) }
        }
    }
}
