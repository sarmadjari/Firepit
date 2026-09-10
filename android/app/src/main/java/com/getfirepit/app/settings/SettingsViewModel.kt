package com.getfirepit.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.Owner
import com.getfirepit.core.data.OwnerRepository
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
    mesh: MeshRepository,
) : ViewModel() {

    val theme: StateFlow<ThemeChoice> = themePreferences.choice

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
