package com.getfirepit.app.chat

import com.getfirepit.core.protocol.ChannelLoad
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.ChatPresence
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.database.ChannelStateDao
import com.getfirepit.core.database.markRead
import com.getfirepit.core.database.observeMuted
import com.getfirepit.core.database.setMuted
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
    /** 2.8 firmware only; drives the signing budget hint. */
    val signingAvailable: Boolean = false,
    val replyingTo: ChatMessage? = null,
    /** The message whose delivery details are being shown, if any. */
    val inspecting: ChatMessage? = null,
    val unread: Map<Int, Int> = emptyMap(),
    val muted: Set<Int> = emptySet(),
    val query: String = "",
    /** Null until our radio reports; absent telemetry is not a clear channel. */
    val channelLoad: ChannelLoad? = null,
) {
    val selectedChannel: RoomChannel? get() = channels.firstOrNull { it.index == selected }
    val draftBytes: Int get() = draft.toByteArray(Charsets.UTF_8).size
    val remainingBytes: Int get() = MeshConstants.MAX_TEXT_BYTES - draftBytes
    val canSend: Boolean get() = connected && draft.isNotBlank() && remainingBytes >= 0

    /** True when this message is long enough that the radio will send it unsigned. */
    val losesSignature: Boolean
        get() = signingAvailable && draftBytes > MeshConstants.SIGNED_BROADCAST_TEXT_BUDGET

    /** Looks up what a message was replying to, if it is still in this channel. */
    fun repliedTo(message: ChatMessage): ChatMessage? =
        message.replyId?.let { id -> messages.firstOrNull { it.id == id } }

    /** Messages matching the search box, or all of them when it is empty. */
    val visibleMessages: List<ChatMessage>
        get() = if (query.isBlank()) {
            messages
        } else {
            messages.filter { it.text.contains(query.trim(), ignoreCase = true) }
        }

    val isSearching: Boolean get() = query.isNotBlank()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val repository: MeshRepository,
    private val channelState: ChannelStateDao,
    private val presence: ChatPresence,
) : ViewModel() {

    private val selected = MutableStateFlow<Int?>(null)
    private val draft = MutableStateFlow("")
    private val error = MutableStateFlow<String?>(null)
    private val replyingTo = MutableStateFlow<ChatMessage?>(null)
    private val inspecting = MutableStateFlow<ChatMessage?>(null)
    private val query = MutableStateFlow("")

    private val messages = selected.flatMapLatest { channel ->
        if (channel == null) flowOf(emptyList()) else repository.observeChannel(channel)
    }

    // Grouped because combine only has typed overloads up to five flows; a
    // sixth silently degrades to Array<Any?>.
    private val composing = combine(draft, error, replyingTo, inspecting, repository.snapshot) {
            draft, error, replyingTo, inspecting, snapshot ->
        Composing(draft, error, replyingTo, inspecting, snapshot?.capabilities?.supportsSigning == true)
    }

    private val readState = combine(
        channelState.observeUnread(),
        channelState.observeMuted(),
        query,
        repository.channelLoad,
    ) { unread, muted, query, load ->
        ReadState(unread.associate { it.channel to it.count }, muted, query, load)
    }

    val uiState: StateFlow<ChatsUiState> = combine(
        repository.isConnected,
        repository.channels,
        selected,
        messages,
        combine(repository.observeNodes(), composing, readState) { nodes, composing, read ->
            Triple(nodes, composing, read)
        },
    ) { connected, channels, selected, messages, (nodes, composing, read) ->
        ChatsUiState(
            connected = connected,
            channels = channels,
            selected = selected,
            messages = messages,
            nodes = nodes.associateBy(MeshNode::nodeNum),
            draft = composing.draft,
            error = composing.error,
            signingAvailable = composing.signingAvailable,
            replyingTo = composing.replyingTo,
            // Re-read from the live list so status ticks keep updating while open.
            inspecting = composing.inspecting?.let { open ->
                messages.firstOrNull { it.id == open.id } ?: open
            },
            unread = read.unread,
            muted = read.muted,
            query = read.query,
            channelLoad = read.channelLoad,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatsUiState())

    init {
        // Reading the open conversation is what marks it read, so a message
        // arriving while it is on screen never becomes a stale badge.
        viewModelScope.launch {
            combine(selected, messages) { channel, messages -> channel to messages }
                .collect { (channel, messages) ->
                    if (channel != null && messages.isNotEmpty()) {
                        channelState.markRead(channel, System.currentTimeMillis())
                    }
                }
        }
    }

    private data class Composing(
        val draft: String,
        val error: String?,
        val replyingTo: ChatMessage?,
        val inspecting: ChatMessage?,
        val signingAvailable: Boolean,
    )

    private data class ReadState(
        val unread: Map<Int, Int>,
        val muted: Set<Int>,
        val query: String,
        val channelLoad: ChannelLoad?,
    )

    fun updateQuery(text: String) {
        query.value = text
    }

    fun toggleMute(channel: Int) {
        viewModelScope.launch {
            channelState.setMuted(channel, channel !in uiState.value.muted)
        }
    }

    fun select(index: Int?) {
        selected.value = index
        error.value = null
        replyingTo.value = null
        inspecting.value = null
        query.value = ""
        presence.setOpenChannel(index)
    }

    fun startReply(message: ChatMessage) {
        replyingTo.value = message
    }

    fun cancelReply() {
        replyingTo.value = null
    }

    fun inspect(message: ChatMessage?) {
        inspecting.value = message
    }

    fun updateDraft(text: String) {
        // Hard stop rather than a warning: the radio would reject the send, and
        // truncating at a byte index would split Arabic or emoji.
        draft.value = MeshConstants.truncateToBytes(text)
        error.value = null
    }

    fun send() {
        val channel = selected.value ?: return
        val text = draft.value.trim()
        if (text.isEmpty()) return
        val replyId = replyingTo.value?.id
        draft.value = ""
        replyingTo.value = null
        viewModelScope.launch {
            runCatching { repository.sendText(channel, text, replyId) }
                .onFailure { cause -> error.value = cause.message ?: "Could not send" }
        }
    }
}
