package com.getfirepit.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.NodeAdminClient
import com.getfirepit.core.data.Owner
import com.getfirepit.core.data.OwnerRepository
import com.getfirepit.core.data.RangeRepository
import com.getfirepit.core.protocol.MessageAlerts
import com.getfirepit.core.protocol.MessageRetention
import com.getfirepit.core.protocol.RadioPrivacy
import com.getfirepit.core.protocol.RangeMode
import com.getfirepit.core.protocol.RoomLifetime
import com.getfirepit.core.protocol.Person
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePreferences: ThemePreferences,
    private val people: PersonStore,
    private val owners: OwnerRepository,
    private val retention: RetentionStore,
    private val notifications: NotificationPreferences,
    private val screenPrivacy: ScreenPrivacyPreferences,
    private val range: RangeRepository,
    private val admin: NodeAdminClient,
    private val mesh: MeshRepository,
) : ViewModel() {

    val theme: StateFlow<ThemeChoice> = themePreferences.choice

    /** How far this node's traffic travels, and whose radios carry it. */
    val rangeMode: StateFlow<RangeMode> = range.mode

    /** Whether this radio's own name and battery are hidden from other Meshtastic devices. */
    val radioPrivacy: StateFlow<RadioPrivacy> = range.privacy

    private val _rangeError = MutableStateFlow<String?>(null)
    val rangeError: StateFlow<String?> = _rangeError.asStateFlow()

    fun chooseRange(choice: RangeMode) {
        viewModelScope.launch {
            _rangeError.value = runCatching { range.choose(choice) }
                .exceptionOrNull()
                ?.let { it.message ?: "Could not change the range setting" }
        }
    }

    /** Takes the radio's primary channel over, hiding its name and battery. */
    fun makeRadioPrivate() {
        viewModelScope.launch {
            _rangeError.value = runCatching { range.makePrivate() }
                .exceptionOrNull()
                ?.let { it.message ?: "Could not change the radio" }
        }
    }

    /** Leaves the radio on whatever primary channel it already had. */
    fun keepRadioPublic() {
        viewModelScope.launch {
            _rangeError.value = runCatching { range.keepPublic() }
                .exceptionOrNull()
                ?.let { it.message ?: "Could not put the radio's own channel back" }
        }
    }

    /** True when Firepit replaced this radio's primary and still has the original. */
    val canRestoreRadio: StateFlow<Boolean> = range.canRestore

    val retentionChoice: StateFlow<MessageRetention> = retention.choice

    val showMessageText: StateFlow<Boolean> = notifications.showText

    fun setShowMessageText(show: Boolean) = notifications.setShowText(show)

    /** Whether Firepit's screens may be captured; off unless somebody asks. */
    val allowScreenCapture: StateFlow<Boolean> = screenPrivacy.allowCapture

    fun setAllowScreenCapture(allow: Boolean) = screenPrivacy.setAllowCapture(allow)

    /** Deletes everything this phone kept about what was said and where anyone was. */
    fun eraseHistory() {
        viewModelScope.launch { retention.eraseHistory() }
    }

    /** Taken from the radio, so it stays right if another app changed it. */
    val messageAlerts: StateFlow<MessageAlerts> = mesh.snapshot
        .map { MessageAlerts.of(it?.externalNotification) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MessageAlerts.PHONE_ONLY)

    private val _alertsError = MutableStateFlow<String?>(null)
    val alertsError: StateFlow<String?> = _alertsError.asStateFlow()

    fun chooseMessageAlerts(choice: MessageAlerts) {
        viewModelScope.launch {
            val current = mesh.snapshot.value?.externalNotification
            _alertsError.value = runCatching {
                admin.setExternalNotificationConfig(MessageAlerts.applyTo(current, choice))
            }.exceptionOrNull()?.let { it.message ?: "Could not change the radio's alert" }
        }
    }

    fun chooseRetention(choice: MessageRetention) {
        viewModelScope.launch { retention.choose(choice) }
    }

    val roomLifetime: StateFlow<RoomLifetime> = retention.roomLifetime

    fun chooseRoomLifetime(choice: RoomLifetime) {
        viewModelScope.launch { retention.chooseRoomLifetime(choice) }
    }

    /** You. Needs no radio, and changing it changes no radio. */
    val person: StateFlow<Person?> = people.person

    val myNodeNum: StateFlow<Int?> = mesh.myNodeNum

    val connected: StateFlow<Boolean> = mesh.isConnected

    fun chooseIdentitySlot(slot: Int?) = people.chooseColour(slot)

    /** What the connected radio calls itself, which the whole mesh can see. */
    val owner: StateFlow<Owner?> =
        owners.owner.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _renameError = MutableStateFlow<String?>(null)
    val renameError: StateFlow<String?> = _renameError.asStateFlow()

    fun chooseTheme(choice: ThemeChoice) = themePreferences.set(choice)

    fun savePerson(name: String, tag: String) {
        _renameError.value = runCatching { people.save(name, tag) }.exceptionOrNull()?.message
    }

    /**
     * Copies your name onto the connected radio.
     *
     * The only name the protocol has is the node's, so this is the one way to
     * be seen as yourself by people who are not running Firepit.
     */
    fun useAsNodeName() {
        val person = people.person.value ?: return
        renameNode(person.name, person.tag)
    }

    fun renameNode(longName: String, shortName: String) {
        viewModelScope.launch {
            _renameError.value = runCatching { owners.rename(longName, shortName) }
                .exceptionOrNull()
                ?.message
                ?: return@launch
        }
    }

    fun clearRenameError() {
        _renameError.value = null
    }
}
