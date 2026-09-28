package com.getfirepit.app.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.MeshRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

data class OfflineMapsUiState(
    val areas: List<OfflineArea> = emptyList(),
    /** 0..1 while a download runs, null when idle. */
    val progress: Float? = null,
    val error: String? = null,
    val offlineOnly: Boolean = false,
)

@HiltViewModel
class OfflineMapsViewModel @Inject constructor(
    private val repository: OfflineMapRepository,
    private val mapPreferences: MapPreferences,
    mesh: MeshRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OfflineMapsUiState())
    val uiState: StateFlow<OfflineMapsUiState> = _uiState.asStateFlow()

    /**
     * The last fix this phone recorded for itself, or null before there is one.
     *
     * Read from the node table rather than the GPS: this screen is about
     * choosing ground, and the last known position is close enough to point the
     * camera at without waking the receiver.
     */
    val myPosition: StateFlow<LatLng?> =
        combine(mesh.observeNodes(), mesh.myNodeNum) { nodes, myNodeNum ->
            nodes.firstOrNull { it.nodeNum == myNodeNum && it.hasPosition }
                ?.let { LatLng(it.latitude!!, it.longitude!!) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        refresh()
        viewModelScope.launch {
            mapPreferences.offlineOnly.collect { enabled ->
                _uiState.update { it.copy(offlineOnly = enabled) }
            }
        }
    }

    fun setOfflineOnly(enabled: Boolean) = mapPreferences.setOfflineOnly(enabled)

    fun download(name: String, bounds: LatLngBounds, styleUrl: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(progress = 0f, error = null) }
            val result = repository.download(name, bounds, styleUrl) { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
            _uiState.update { state ->
                state.copy(
                    progress = null,
                    error = result.exceptionOrNull()?.message,
                )
            }
            refresh()
        }
    }

    fun delete(area: OfflineArea) {
        viewModelScope.launch {
            repository.delete(area)
                .onFailure { cause -> _uiState.update { it.copy(error = cause.message) } }
            refresh()
        }
    }

    fun update(area: OfflineArea) {
        viewModelScope.launch {
            _uiState.update { it.copy(progress = 0f, error = null) }
            val result = repository.update(area) { progress ->
                _uiState.update { it.copy(progress = progress) }
            }
            _uiState.update { state ->
                state.copy(progress = null, error = result.exceptionOrNull()?.message)
            }
            refresh()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(areas = repository.areas()) }
        }
    }
}
