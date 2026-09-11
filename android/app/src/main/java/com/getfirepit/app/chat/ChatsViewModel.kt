package com.getfirepit.app.chat

import com.getfirepit.core.protocol.ChannelLoad
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.ChatPresence
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.ReceiptRepository
import com.getfirepit.core.model.Receipt
import com.getfirepit.core.database.ChannelStateDao
import com.getfirepit.core.database.markRead
import com.getfirepit.core.database.observeMuted
import com.getfirepit.core.database.setMuted
import com.getfirepit.core.model.ChatMessage
import com.getfirepit.app.settings.PersonStore
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.protocol.Person
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
    /** Our own node, for the header's name and battery. */
    val myNode: MeshNode? = null,
    val person: Person? = null,
    /** Newest message per channel, for the list previews. */
    val latest: Map<Int, ChatMessage> = emptyMap(),
    /** The person whose conversation is open, if it is a direct one. */
    val directPeer: Int? = null,
    /** Newest message per person, for the Direct list. */
    val directLatest: List<ChatMessage> = emptyList(),
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
    private val people: PersonStore,
    private val receipts: ReceiptRepository,
) : ViewModel() {

    private val selected = MutableStateFlow<Int?>(null)
    private val directPeer = MutableStateFlow<Int?>(null)
    private val draft = MutableStateFlow("")
    private val error = MutableStateFlow<String?>(null)
    private val replyingTo = MutableStateFlow<ChatMessage?>(null)
    private val inspecting = MutableStateFlow<ChatMessage?>(null)
    private val query = MutableStateFlow("")

    private val messages = combine(selected, directPeer) { channel, peer -> channel to peer }
        .flatMapLatest { (channel, peer) ->
            when {
                peer != null -> repository.observeDirect(peer)
                channel != null -> repository.observeChannel(channel)
                else -> flowOf(emptyList())
            }
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
        combine(repository.observeLatestPerChannel(), repository.observeDirectLatest(), directPeer) {
                latest, direct, peer ->
            Triple(latest, direct, peer)
        },
    ) { unread, muted, query, load, (latest, direct, peer) ->
        ReadState(
            unread = unread.associate { it.channel to it.count },
            muted = muted,
            query = query,
            channelLoad = load,
            latest = latest.associateBy { it.channel },
            directLatest = direct.sortedByDescending { it.sentAt },
            directPeer = peer,
        )
    }

    val uiState: StateFlow<ChatsUiState> = combine(
        repository.isConnected,
        repository.channels,
        selected,
        messages,
        combine(
            repository.observeNodes(),
            composing,
            readState,
            people.person,
        ) { nodes, composing, read, person ->
            Self(nodes, composing, read, person)
        },
    ) { connected, channels, selected, messages, self ->
        ChatsUiState(
            connected = connected,
            channels = channels,
            selected = selected,
            messages = messages,
            nodes = self.nodes.associateBy(MeshNode::nodeNum),
            myNode = self.nodes.firstOrNull { it.nodeNum == repository.myNodeNum.value },
            person = self.person,
            latest = self.read.latest,
            draft = self.composing.draft,
            error = self.composing.error,
            signingAvailable = self.composing.signingAvailable,
            replyingTo = self.composing.replyingTo,
            // Re-read from the live list so status ticks keep updating while open.
            inspecting = self.composing.inspecting?.let { open ->
                messages.firstOrNull { it.id == open.id } ?: open
            },
            unread = self.read.unread,
            muted = self.read.muted,
            query = self.read.query,
            channelLoad = self.read.channelLoad,
            directPeer = self.read.directPeer,
            directLatest = self.read.directLatest,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatsUiState())

    /**
     * Kept out of the main state because combine has typed overloads only to
     * five flows, and only the info sheet reads this.
     */
    val inspectedReceipts: StateFlow<List<Receipt>> = inspecting
        .flatMapLatest { message ->
            message?.let { receipts.observe(it.id) } ?: flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // On screen and looked at is the only honest definition of read.
        viewModelScope.launch {
            combine(messages, selected, directPeer, presence.foreground) {
                    shown, channel, peer, foreground ->
                if (!foreground || (channel == null && peer == null)) null
                else Triple(channel ?: 0, peer, shown.filterNot { it.isOutgoing }.map { it.id }.toSet())
            }.collect { open ->
                open?.let { (channel, peer, ids) -> receipts.read(channel, ids, peer) }
            }
        }
    }

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

    /** Combine takes five sources at most; these four travel together. */
    private data class Self(
        val nodes: List<MeshNode>,
        val composing: Composing,
        val read: ReadState,
        val person: Person?,
    )

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
        val latest: Map<Int, ChatMessage>,
        val directLatest: List<ChatMessage>,
        val directPeer: Int?,
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
        directPeer.value = null
        clearComposing()
        presence.setOpenChannel(index)
    }

    /** Opens the conversation with one person. */
    fun openDirect(peer: Int) {
        directPeer.value = peer
        selected.value = null
        clearComposing()
        // Not a channel, so nothing to suppress notifications for by index.
        presence.setOpenChannel(null)
    }

    private fun clearComposing() {
        error.value = null
        replyingTo.value = null
        inspecting.value = null
        query.value = ""
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
        val peer = directPeer.value
        val channel = selected.value
        if (peer == null && channel == null) return
        val text = draft.value.trim()
        if (text.isEmpty()) return
        val replyId = replyingTo.value?.id
        draft.value = ""
        replyingTo.value = null
        viewModelScope.launch {
            runCatching {
                if (peer != null) {
                    // The primary channel: the one every node on the mesh shares,
                    // so a direct message can be decrypted at the far end.
                    repository.sendText(channel = 0, text = text, replyId = replyId, to = peer)
                } else {
                    repository.sendText(channel = channel!!, text = text, replyId = replyId)
                }
            }.onFailure { cause -> error.value = cause.message ?: "Could not send" }
        }
    }
}
