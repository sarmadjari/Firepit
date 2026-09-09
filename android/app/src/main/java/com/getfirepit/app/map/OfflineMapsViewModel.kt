package com.getfirepit.app.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLngBounds

data class OfflineMapsUiState(
    val areas: List<OfflineArea> = emptyList(),
    /** 0..1 while a download runs, null when idle. */
    val progress: Float? = null,
    val error: String? = null,
)

@HiltViewModel
class OfflineMapsViewModel @Inject constructor(
    private val repository: OfflineMapRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OfflineMapsUiState())
    val uiState: StateFlow<OfflineMapsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

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
