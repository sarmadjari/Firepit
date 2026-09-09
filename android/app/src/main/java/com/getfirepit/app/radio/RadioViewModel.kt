package com.getfirepit.app.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.protocol.RadioCapabilities
import com.getfirepit.core.transport.DiscoveredRadio
import com.getfirepit.core.transport.LinkState
import com.getfirepit.core.transport.RadioLink
import com.getfirepit.core.transport.RadioScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChannelRow(val index: Int, val role: String, val name: String, val precision: Int)

data class RadioDetails(
    val nodeId: String,
    val nodeNum: Int,
    val firmware: String,
    val hardware: String,
    val region: String,
    val rebootCount: Int,
    val capabilities: RadioCapabilities,
    val channels: List<ChannelRow>,
    val knownNodes: Int,
)

data class RadioUiState(
    val scanning: Boolean = false,
    val found: List<DiscoveredRadio> = emptyList(),
    val link: LinkState = LinkState.Disconnected,
    val details: RadioDetails? = null,
    val error: String? = null,
)

@HiltViewModel
class RadioViewModel @Inject constructor(
    private val scanner: RadioScanner,
    private val link: RadioLink,
) : ViewModel() {

    private val scanning = MutableStateFlow(false)
    private val found = MutableStateFlow(emptyList<DiscoveredRadio>())
    private val error = MutableStateFlow<String?>(null)
    private var scanJob: Job? = null

    val uiState: StateFlow<RadioUiState> =
        combine(scanning, found, link.state, error) { scanning, found, linkState, error ->
            RadioUiState(
                scanning = scanning,
                found = found,
                link = linkState,
                details = (linkState as? LinkState.Ready)?.snapshot?.toDetails(),
                error = error,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadioUiState())

    fun startScan() {
        if (scanJob?.isActive == true) return
        error.value = null
        scanning.value = true
        scanJob = viewModelScope.launch {
            scanner.scanDistinct()
                .catch { cause -> error.value = cause.message ?: "Scan failed" }
                .collect { found.value = it }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanning.value = false
    }

    fun connect(radio: DiscoveredRadio) {
        stopScan()
        error.value = null
        link.connect(radio)
    }

    fun disconnect() {
        viewModelScope.launch { link.disconnect() }
    }
}

private fun com.getfirepit.core.protocol.phoneapi.RadioSnapshot.toDetails() = RadioDetails(
    nodeId = myNodeNum?.let(MeshConstants::formatNodeId).orEmpty(),
    nodeNum = myNodeNum ?: 0,
    firmware = metadata?.firmware_version.orEmpty(),
    hardware = metadata?.hw_model?.name.orEmpty(),
    region = lora?.region?.name ?: "UNSET",
    rebootCount = myInfo?.reboot_count ?: 0,
    capabilities = capabilities,
    channels = channels.values.sortedBy { it.index }.map { channel ->
        ChannelRow(
            index = channel.index,
            role = channel.role.name,
            name = channel.settings?.name?.ifBlank { "(preset name)" } ?: "",
            precision = channel.settings?.module_settings?.position_precision ?: 0,
        )
    },
    knownNodes = nodes.size,
)
