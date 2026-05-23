package com.mukapp.mote.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.mukapp.mote.R
import com.mukapp.mote.data.model.ConversationSummary
import com.mukapp.mote.databinding.ItemConversationSummaryBinding
import java.util.UUID

class ConversationSummaryAdapter(
    private val onConversationClick: (ConversationSummary) -> Unit,
    private val onConversationLongClick: ((ConversationSummary) -> Unit)? = null
) : RecyclerView.Adapter<ConversationSummaryAdapter.ViewHolder>() {
    private val items = mutableListOf<ConversationSummary>()
    private var currentConversationId: String = ""

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return stableItemId(items[position].id)
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
                com.google.android.material.R.attr.colorSurfaceContainer
            }
            val textAttr = if (selected) {
                com.google.android.material.R.attr.colorOnSecondaryContainer
            } else {
                com.google.android.material.R.attr.colorOnSurface
            }
            val subtextAttr = if (selected) {
                com.google.android.material.R.attr.colorOnSecondaryContainer
            } else {
                com.google.android.material.R.attr.colorOnSurfaceVariant
            }
            binding.cardConversation.setCardBackgroundColor(
                MaterialColors.getColor(binding.cardConversation, backgroundAttr)
            )
            binding.textTitle.setTextColor(MaterialColors.getColor(binding.textTitle, textAttr))
            binding.textTitle.text = item.title.ifBlank { context.getString(R.string.nav_untitled_chat) }

            // 显示相对时间
            val timeText = if (item.updatedAt > 0) {
                DateUtils.getRelativeTimeSpanString(
                    item.updatedAt,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
            } else {
                ""
            }
            binding.textTime.text = timeText
            binding.textTime.setTextColor(MaterialColors.getColor(binding.textTime, subtextAttr))

            binding.root.isSelected = selected
            binding.root.setOnClickListener { onConversationClick(item) }
            binding.root.setOnLongClickListener {
                onConversationLongClick?.invoke(item)
                onConversationLongClick != null
            }
        }
    }

    private companion object {
        fun stableItemId(id: String): Long {
            return runCatching {
                val uuid = UUID.fromString(id)
                uuid.mostSignificantBits xor uuid.leastSignificantBits
            }.getOrElse {
                id.fold(1125899906842597L) { hash, char -> hash * 31 + char.code }
            }
        }
    }
}
