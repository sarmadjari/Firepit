package com.getfirepit.app.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.model.RoomChannel
import com.getfirepit.core.protocol.MeshConstants
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatsUiState(
    val connected: Boolean = false,
    val channels: List<RoomChannel> = emptyList(),
    val selected: Int? = null,
    val messages: List<ChatMessage> = emptyList(),
    val nodes: Map<Int, MeshNode> = emptyMap(),
    val draft: String = "",
    val error: String? = null,
) {
    val selectedChannel: RoomChannel? get() = channels.firstOrNull { it.index == selected }
    val draftBytes: Int get() = draft.toByteArray(Charsets.UTF_8).size
    val remainingBytes: Int get() = MeshConstants.MAX_TEXT_BYTES - draftBytes
    val canSend: Boolean get() = connected && draft.isNotBlank() && remainingBytes >= 0
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val repository: MeshRepository,
) : ViewModel() {

    private val selected = MutableStateFlow<Int?>(null)
    private val draft = MutableStateFlow("")
    private val error = MutableStateFlow<String?>(null)

    private val messages = selected.flatMapLatest { channel ->
        if (channel == null) flowOf(emptyList()) else repository.observeChannel(channel)
    }

    val uiState: StateFlow<ChatsUiState> = combine(
        repository.isConnected,
        repository.channels,
        selected,
        messages,
        combine(repository.observeNodes(), draft, error) { nodes, draft, error -> Triple(nodes, draft, error) },
    ) { connected, channels, selected, messages, extras ->
        val (nodes, draft, error) = extras
        ChatsUiState(
            connected = connected,
            channels = channels,
            selected = selected,
            messages = messages,
            nodes = nodes.associateBy(MeshNode::nodeNum),
            draft = draft,
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatsUiState())

    fun select(index: Int?) {
        selected.value = index
        error.value = null
    }

    fun updateDraft(text: String) {
        draft.value = text
        error.value = null
    }

    fun send() {
        val channel = selected.value ?: return
        val text = draft.value.trim()
        if (text.isEmpty()) return
        draft.value = ""
        viewModelScope.launch {
            runCatching { repository.sendText(channel, text) }
                .onFailure { cause -> error.value = cause.message ?: "Could not send" }
        }
    }
}
