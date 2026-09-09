package com.getfirepit.app.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.protocol.MeshConstants
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val channel: Int = 0,
    val draft: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val nodes: Map<Int, MeshNode> = emptyMap(),
    val error: String? = null,
) {
    val draftBytes: Int get() = draft.toByteArray(Charsets.UTF_8).size
    val remainingBytes: Int get() = MeshConstants.MAX_TEXT_BYTES - draftBytes
    val canSend: Boolean get() = draft.isNotBlank() && remainingBytes >= 0
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: MeshRepository,
) : ViewModel() {

    private val channel = MutableStateFlow(0)
    private val draft = MutableStateFlow("")
    private val error = MutableStateFlow<String?>(null)

    @Suppress("OPT_IN_USAGE")
    val uiState: StateFlow<ChatUiState> = combine(
        channel,
        draft,
        channel.flatMapLatest { repository.observeChannel(it) },
        repository.observeNodes(),
        error,
    ) { channel, draft, messages, nodes, error ->
        ChatUiState(
            channel = channel,
            draft = draft,
            messages = messages,
            nodes = nodes.associateBy(MeshNode::nodeNum),
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    fun selectChannel(index: Int) {
        channel.value = index
    }

    fun updateDraft(text: String) {
        draft.value = text
        error.value = null
    }

    fun send() {
        val text = draft.value.trim()
        if (text.isEmpty()) return
        draft.value = ""
        viewModelScope.launch {
            runCatching { repository.sendText(channel.value, text) }
                .onFailure { cause -> error.value = cause.message ?: "Could not send" }
        }
    }
}
