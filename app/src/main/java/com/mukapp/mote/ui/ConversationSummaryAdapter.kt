package com.mukapp.mote.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.mukapp.mote.R
import com.mukapp.mote.data.model.ConversationSummary
import com.mukapp.mote.databinding.ItemConversationSummaryBinding
import java.util.UUID

class ConversationSummaryAdapter(
    private val onConversationClick: (ConversationSummary) -> Unit,
    private val onConversationLongClick: ((view: View, summary: ConversationSummary) -> Unit)? = null
) : RecyclerView.Adapter<ConversationSummaryAdapter.ViewHolder>() {
    private var items: List<ConversationSummary> = emptyList()
    private var timeTexts: List<String> = emptyList()
    private var currentConversationId: String = ""

    private data class SummaryPayload(
        val contentChanged: Boolean,
        val selectionChanged: Boolean
    )

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
        holder.bind(items[position], timeTexts[position])
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        var contentChanged = false
        var selectionChanged = false
        for (payload in payloads) {
            if (payload !is SummaryPayload) {
                onBindViewHolder(holder, position)
                return
            }
            contentChanged = contentChanged || payload.contentChanged
            selectionChanged = selectionChanged || payload.selectionChanged
        }
        if (contentChanged) {
            holder.bindContent(items[position], timeTexts[position])
        }
        if (selectionChanged) {
            holder.bindSelection(items[position].id == currentConversationId)
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitItems(newItems: List<ConversationSummary>, selectedId: String) {
        val oldItems = items
        val oldTimeTexts = timeTexts
        val oldSelectedId = currentConversationId
        val nextItems = newItems.toList()
        val now = System.currentTimeMillis()
        val nextTimeTexts = nextItems.map { formatRelativeTime(it.updatedAt, now) }
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = nextItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldItems[oldItemPosition].id == nextItems[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = oldItems[oldItemPosition]
                val new = nextItems[newItemPosition]
                return old == new &&
                    oldTimeTexts[oldItemPosition] == nextTimeTexts[newItemPosition] &&
                    (old.id == oldSelectedId) == (new.id == selectedId)
            }

            override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): Any {
                val old = oldItems[oldItemPosition]
                val new = nextItems[newItemPosition]
                return SummaryPayload(
                    contentChanged = old != new ||
                        oldTimeTexts[oldItemPosition] != nextTimeTexts[newItemPosition],
                    selectionChanged = (old.id == oldSelectedId) != (new.id == selectedId)
                )
            }
        }, true)
        items = nextItems
        timeTexts = nextTimeTexts
        currentConversationId = selectedId
        diffResult.dispatchUpdatesTo(this)
    }

    inner class ViewHolder(
        private val binding: ItemConversationSummaryBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                currentItemOrNull()?.let(onConversationClick)
            }
            binding.root.setOnLongClickListener { view ->
                val item = currentItemOrNull() ?: return@setOnLongClickListener false
                onConversationLongClick?.invoke(view, item)
                onConversationLongClick != null
            }
        }

        fun bind(item: ConversationSummary, timeText: String) {
            bindContent(item, timeText)
            bindSelection(item.id == currentConversationId)
        }

        fun bindContent(item: ConversationSummary, timeText: String) {
            binding.textTitle.text = item.title.ifBlank {
                binding.root.context.getString(R.string.nav_untitled_chat)
            }
            binding.textTime.text = timeText
        }

        fun bindSelection(selected: Boolean) {
            val context = binding.root.context
            val backgroundColor = ContextCompat.getColor(
                context,
                if (selected) R.color.mote_primary_container else R.color.mote_card
            )
            val textColor = ContextCompat.getColor(
                context,
                if (selected) R.color.mote_on_primary_container else R.color.mote_on_background
            )
            val subtextColor = ContextCompat.getColor(
                context,
                if (selected) R.color.mote_on_primary_container else R.color.mote_on_background_secondary
            )
            binding.cardConversation.setCardBackgroundColor(backgroundColor)
            binding.textTitle.setTextColor(textColor)
            binding.textTime.setTextColor(subtextColor)

            binding.root.isSelected = selected
        }

        private fun currentItemOrNull(): ConversationSummary? {
            val position = bindingAdapterPosition
            return if (position != RecyclerView.NO_POSITION && position in items.indices) {
                items[position]
            } else {
                null
            }
        }
    }

    private companion object {
        fun formatRelativeTime(updatedAt: Long, now: Long): String {
            return if (updatedAt > 0) {
                DateUtils.getRelativeTimeSpanString(
                    updatedAt,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                ).toString()
            } else {
                ""
            }
        }

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
