package com.mukapp.mote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mukapp.mote.R
import com.mukapp.mote.data.model.ApiSettings
import com.mukapp.mote.data.model.ChatMessage
import com.mukapp.mote.databinding.FragmentChatBinding

class ChatFragment : Fragment() {
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChatViewModel by activityViewModels()
    private lateinit var adapter: ChatMessageAdapter

    private var latestMessages: List<ChatMessage> = emptyList()
    private var latestSettings: ApiSettings = ApiSettings()
    private var latestIsSending: Boolean = false
    private var followOutput: Boolean = true
    private var updatingDraft: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupInputArea()
        observeViewModel()
    }

    override fun onDestroyView() {
        binding.recyclerMessages.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun setupRecyclerView() {
        adapter = ChatMessageAdapter(
            onCopyMessage = { message -> copyMessage(message) },
            onEditMessage = { index -> showConfirmationDialog(R.string.dialog_edit_title, R.string.dialog_edit_message) { viewModel.editMessage(index) } },
            onDeleteMessage = { index -> showConfirmationDialog(R.string.dialog_delete_title, R.string.dialog_delete_message) { viewModel.deleteMessage(index) } },
            onRetryMessage = { index -> showConfirmationDialog(R.string.dialog_retry_title, R.string.dialog_retry_message) { viewModel.retryMessage(index) } }
        )

        binding.recyclerMessages.layoutManager = object : LinearLayoutManager(requireContext()) {
            override fun smoothScrollToPosition(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                state: androidx.recyclerview.widget.RecyclerView.State,
                position: Int
            ) {
                val smoothScroller = object : androidx.recyclerview.widget.LinearSmoothScroller(recyclerView.context) {
                    override fun getVerticalSnapPreference(): Int {
                        return SNAP_TO_END
                    }
                }
                smoothScroller.targetPosition = position
                startSmoothScroll(smoothScroller)
            }
        }
        binding.recyclerMessages.adapter = adapter
        binding.recyclerMessages.itemAnimator = null
        binding.recyclerMessages.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                followOutput = !recyclerView.canScrollVertically(1)
            }
        })
    }

    private fun setupInputArea() {
        binding.editMessage.doAfterTextChanged { editable ->
            if (!updatingDraft) {
                viewModel.updateDraftMessage(editable?.toString().orEmpty())
            }
        }
        binding.btnSend.setOnClickListener {
            viewModel.sendMessage()
        }
    }

    private fun observeViewModel() {
        viewModel.uiMessages.observe(viewLifecycleOwner) { messages ->
            latestMessages = messages
            renderMessages()
            renderEmptyState()
        }

        viewModel.savedSettings.observe(viewLifecycleOwner) { settings ->
            latestSettings = settings
            renderEmptyState()
        }

        viewModel.isSending.observe(viewLifecycleOwner) { sending ->
            latestIsSending = sending
            renderMessages()
            binding.btnSend.isEnabled = !sending && binding.editMessage.text?.isNotBlank() == true
            binding.editMessage.isEnabled = !sending
        }

        viewModel.draftMessage.observe(viewLifecycleOwner) { draft ->
            val current = binding.editMessage.text?.toString().orEmpty()
            if (current != draft) {
                updatingDraft = true
                binding.editMessage.setText(draft)
                binding.editMessage.setSelection(draft.length)
                updatingDraft = false
            }
            binding.btnSend.isEnabled = !latestIsSending && draft.isNotBlank()
        }
    }

    private fun renderMessages() {
        adapter.submitMessages(latestMessages, latestIsSending)
        if (adapter.itemCount > 0 && followOutput) {
            binding.recyclerMessages.post {
                scrollToBottom()
            }
        }
    }

    private fun scrollToBottom() {
        val layoutManager = binding.recyclerMessages.layoutManager as? LinearLayoutManager ?: return
        val lastPosition = adapter.itemCount - 1
        if (lastPosition < 0) {
            return
        }

        val viewHolder = binding.recyclerMessages.findViewHolderForAdapterPosition(lastPosition)
        if (viewHolder != null && viewHolder.itemView.height > 0) {
            val itemHeight = viewHolder.itemView.height
            val offset = binding.recyclerMessages.height -
                binding.recyclerMessages.paddingTop -
                binding.recyclerMessages.paddingBottom -
                itemHeight
            layoutManager.scrollToPositionWithOffset(lastPosition, offset)
        } else {
            layoutManager.scrollToPosition(lastPosition)
        }
    }

    private fun renderEmptyState() {
        val showEmptyState = latestMessages.isEmpty()
        binding.emptyPlaceholder.root.visibility = if (showEmptyState) View.VISIBLE else View.GONE
        binding.recyclerMessages.visibility = if (showEmptyState) View.INVISIBLE else View.VISIBLE
        binding.emptyPlaceholder.textEmptySubtitle.text = getString(
            if (latestSettings.baseUrl.isNotBlank() && latestSettings.model.isNotBlank()) {
                R.string.empty_subtitle_configured
            } else {
                R.string.empty_subtitle_unconfigured
            }
        )
        binding.emptyPlaceholder.textEmptyTipsBody.text = getString(
            if (latestSettings.baseUrl.isNotBlank() && latestSettings.model.isNotBlank()) {
                R.string.empty_tips_configured
            } else {
                R.string.empty_tips_unconfigured
            }
        )
    }

    private fun copyMessage(message: ChatMessage) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), message.content))
        Toast.makeText(requireContext(), getString(R.string.action_copy), Toast.LENGTH_SHORT).show()
    }

    private fun showConfirmationDialog(titleResId: Int, messageResId: Int, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleResId)
            .setMessage(messageResId)
            .setPositiveButton(R.string.action_confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
