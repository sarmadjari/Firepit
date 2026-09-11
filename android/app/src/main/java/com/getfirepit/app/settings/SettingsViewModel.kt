package com.getfirepit.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.Owner
import com.getfirepit.core.data.OwnerRepository
import com.getfirepit.core.protocol.MessageRetention
import com.getfirepit.core.protocol.RoomLifetime
import com.getfirepit.core.protocol.Person
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val themePreferences: ThemePreferences,
    private val people: PersonStore,
    private val owners: OwnerRepository,
    private val retention: RetentionStore,
    private val notifications: NotificationPreferences,
    mesh: MeshRepository,
) : ViewModel() {

    val theme: StateFlow<ThemeChoice> = themePreferences.choice

    val retentionChoice: StateFlow<MessageRetention> = retention.choice

    val showMessageText: StateFlow<Boolean> = notifications.showText

    fun setShowMessageText(show: Boolean) = notifications.setShowText(show)

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
