package com.getfirepit.app.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.WaypointRepository
import com.getfirepit.core.model.MapPin
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PinsUiState(
    val pins: List<MapPin> = emptyList(),
    val myNodeNum: Int? = null,
    val error: String? = null,
)

@HiltViewModel
class PinsViewModel @Inject constructor(
    private val waypoints: WaypointRepository,
    mesh: MeshRepository,
) : ViewModel() {

    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PinsUiState> = combine(
        waypoints.observePins(),
        mesh.myNodeNum,
        error,
    ) { pins, myNodeNum, error ->
        PinsUiState(
            pins = pins.sortedBy { it.name.lowercase() },
            myNodeNum = myNodeNum,
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PinsUiState())

    fun rename(pin: MapPin, name: String) = run("Could not rename the pin") {
        waypoints.rename(pin, name)
    }

    fun remove(pin: MapPin) = run("Could not delete the pin") { waypoints.remove(pin) }

    private fun run(fallback: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            error.value = null
            runCatching { block() }.onFailure { cause -> error.value = cause.message ?: fallback }
        }
    }
}
