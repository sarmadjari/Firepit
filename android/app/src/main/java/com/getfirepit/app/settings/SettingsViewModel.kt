package com.getfirepit.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.Owner
import com.getfirepit.core.data.OwnerRepository
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
    private val owners: OwnerRepository,
    mesh: MeshRepository,
) : ViewModel() {

    val theme: StateFlow<ThemeChoice> = themePreferences.choice

    val connected: StateFlow<Boolean> = mesh.isConnected

    val owner: StateFlow<Owner?> =
        owners.owner.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _renameError = MutableStateFlow<String?>(null)
    val renameError: StateFlow<String?> = _renameError.asStateFlow()

    fun chooseTheme(choice: ThemeChoice) = themePreferences.set(choice)

    fun rename(longName: String, shortName: String) {
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
