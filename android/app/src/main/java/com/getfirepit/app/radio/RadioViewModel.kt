package com.getfirepit.app.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.getfirepit.core.data.AlertClient
import com.getfirepit.core.data.BuzzResult
import com.getfirepit.core.data.LocationSettingsStore
import com.getfirepit.core.data.MeshRepository
import com.getfirepit.core.data.NodeAdminClient
import com.getfirepit.core.data.Owner
import com.getfirepit.core.data.OwnerRepository
import com.getfirepit.core.data.RoomRepository
import com.getfirepit.core.data.SessionStore
import com.getfirepit.core.data.TracerouteClient
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.protocol.BeaconRate
import com.getfirepit.core.protocol.ChannelKey
import com.getfirepit.core.protocol.MeshConstants
import com.getfirepit.core.protocol.NodeRole
import com.getfirepit.core.protocol.PrimaryChannel
import com.getfirepit.core.protocol.RadioCapabilities
import com.getfirepit.core.protocol.RadioRisk
import com.getfirepit.core.protocol.RadioSecurityCheck
import com.getfirepit.core.protocol.RelayReach
import com.getfirepit.core.protocol.SavedRadio
import com.getfirepit.core.protocol.TrustRules
import com.getfirepit.core.transport.BluetoothPresence
import com.getfirepit.core.transport.BluetoothState
import com.getfirepit.core.transport.DiscoveredRadio
import com.getfirepit.core.transport.LinkState
import com.getfirepit.core.transport.RadioLink
import com.getfirepit.core.transport.RadioScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.proto.Config
import org.meshtastic.proto.Routing

data class ChannelRow(
    val index: Int,
    val role: String,
    val name: String,
    val precision: Int,
    val key: ChannelKey,
    /** What the key is, in words, naming the app-wide key for what it is. */
    val keyLabel: String = key.label,
)

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
    /** Something that happened but did not fail, and is not worth a dialog. */
    val notice: String? = null,
    val nodes: List<MeshNode> = emptyList(),
    val myNodeNum: Int? = null,
    val tracing: Int? = null,
    val traceResult: String? = null,
    val saved: List<SavedRadio> = emptyList(),
    val owner: Owner? = null,
    /** Which saved radio this session is talking to, if any. */
    val connectedTo: String? = null,
    /**
     * The radio this session is bound to, connected or not yet.
     *
     * Only one radio can be administered at a time, so this is what tells three
     * live radios apart from the one the app is actually talking to.
     */
    val activeRadioId: String? = null,
    /** What the phone's Bluetooth stack holds, which is wider than our own link. */
    val bluetooth: BluetoothState = BluetoothState(),
    /** Only known for the radio on the other end of the link. */
    val relayReach: RelayReach? = null,
    val beaconRate: BeaconRate? = null,
    val beaconWhenMoved: Boolean = false,
    val radioGpsAvailable: Boolean = false,
    val radioGpsEnabled: Boolean = false,
    /** Settings on the connected radio that let people read it or run it. */
    val risks: List<RadioRisk> = emptyList(),
    /** A Bluetooth PIN just set, shown until dismissed: the phone will ask for it. */
    val newPin: Int? = null,
    /** The saved radio whose address answered as a different node or key. */
    val identityDoubt: String? = null,
)

@HiltViewModel
class RadioViewModel @Inject constructor(
    private val scanner: RadioScanner,
    private val link: RadioLink,
    private val presence: BluetoothPresence,
    private val session: RadioSessionController,
    private val savedRadios: SavedRadioStore,
    private val sessionStore: SessionStore,
    private val owners: OwnerRepository,
    private val admin: NodeAdminClient,
    private val mesh: MeshRepository,
    private val alerts: AlertClient,
    private val traceroute: TracerouteClient,
    private val rooms: RoomRepository,
    private val locationSettings: LocationSettingsStore,
) : ViewModel() {

    private val scanning = MutableStateFlow(false)
    private val found = MutableStateFlow(emptyList<DiscoveredRadio>())
    private val error = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private val tracing = MutableStateFlow<Int?>(null)
    private val traceResult = MutableStateFlow<String?>(null)
    private val newPin = MutableStateFlow<Int?>(null)
    private var scanJob: Job? = null
    private var noticeJob: Job? = null

    val uiState: StateFlow<RadioUiState> = combine(
        combine(scanning, found, link.state, error, notice) { scanning, found, linkState, error, notice ->
            RadioUiState(
                scanning = scanning,
                found = found,
                link = linkState,
                details = (linkState as? LinkState.Ready)?.snapshot?.toDetails(),
                error = error,
                notice = notice,
            )
        },
        mesh.observeNodes(),
        mesh.myNodeNum,
        combine(
            tracing,
            traceResult,
            savedRadios.radios,
            owners.owner,
            combine(newPin, session.identityDoubt, locationSettings.settings) { pin, doubt, location -> Triple(pin, doubt, location) },
        ) { tracing, result, saved, owner, extra ->
            Extras(tracing, result, saved, owner, extra.first, extra.second, extra.third)
        },
        presence.state(),
    ) { base, nodes, me, extras, bluetooth ->
        val linkState = base.link
        base.copy(
            // A radio is saved under its Bluetooth name, which the firmware only
            // re-advertises after a reboot. What it calls itself on the mesh
            // changes the moment it is renamed, so that is what the list shows.
            saved = extras.saved.map { radio ->
                val meshName = radio.nodeNum
                    ?.let { num -> nodes.firstOrNull { it.nodeNum == num } }
                    ?.longName
                    ?.takeIf { it.isNotBlank() }
                if (meshName == null) radio else radio.copy(name = meshName)
            },
            owner = extras.owner,
            relayReach = RelayReach.of(
                (linkState as? LinkState.Ready)?.snapshot?.device?.rebroadcast_mode,
            ),
            beaconRate = BeaconRate.of(extras.location.rateSeconds),
            beaconWhenMoved = extras.location.whenMoved,
            radioGpsAvailable = (linkState as? LinkState.Ready)?.snapshot?.position?.gps_mode
                ?.let { it != Config.PositionConfig.GpsMode.NOT_PRESENT } == true,
            radioGpsEnabled = (linkState as? LinkState.Ready)?.snapshot?.position?.gps_mode ==
                Config.PositionConfig.GpsMode.ENABLED,
            risks = (linkState as? LinkState.Ready)?.snapshot?.let(RadioSecurityCheck::risksOf).orEmpty(),
            newPin = extras.newPin,
            identityDoubt = extras.identityDoubt,
            // The radio actually on the other end of the link, which is not
            // necessarily the Personal one once Base stations are administered.
            connectedTo = (linkState as? LinkState.Ready)?.let { sessionStore.lastRadioId },
            activeRadioId = sessionStore.lastRadioId,
            bluetooth = bluetooth,
            // Nearest first: the ones you can actually reach matter most.
            // 0L, not 0: mixing Long and Int here erases the selector type and
            // throws when the comparator meets both.
            nodes = nodes.sortedWith(
                compareByDescending<MeshNode> { it.nodeNum == me }
                    .thenBy { it.hopsAway ?: Int.MAX_VALUE }
                    .thenByDescending { it.lastHeard ?: 0L },
            ),
            myNodeNum = me,
            tracing = extras.tracing,
            traceResult = extras.result,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadioUiState())

    fun checkPath(node: MeshNode) {
        if (tracing.value != null) return
        viewModelScope.launch {
            tracing.value = node.nodeNum
            val name = node.displayName
            val result = runCatching { traceroute.trace(node.nodeNum) }
            tracing.value = null
            traceResult.value = result.fold(
                onSuccess = { trace ->
                    when {
                        trace == null -> "No reply from $name within a minute. It may be out of range."
                        trace.isDirect -> "$name answered directly, no relay in between."
                        else -> "$name is ${trace.hopsOut} hops away, via " +
                            trace.towards.joinToString(", ") { hop ->
                                MeshConstants.formatNodeId(hop.nodeNum) +
                                    (hop.snr?.let { " (%.1f dB)".format(it) } ?: "")
                            }
                    }
                },
                onFailure = { cause -> cause.message ?: "Could not trace the route" },
            )
        }
    }

    fun clearTrace() {
        traceResult.value = null
    }

    fun startScan() {
        if (scanJob?.isActive == true) return
        error.value = null
        scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                // Bounded, because a BLE scan left running costs battery for as
                // long as the screen stays open.
                withTimeoutOrNull(SCAN_WINDOW) {
                    scanner.scanDistinct()
                        .catch { cause -> error.value = cause.message ?: "Scan failed" }
                        .collect { found.value = it }
                }
            } finally {
                scanning.value = false
            }
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
        session.connect(radio)
    }

    /** Connects to a radio already known, without waiting for a scan to find it. */
    fun connectSaved(saved: SavedRadio) {
        error.value = null
        viewModelScope.launch {
            // One link at a time: switching radios means letting go of the
            // current one first, or the new connection races the old.
            session.disconnect()
            val found = runCatching {
                withTimeoutOrNull(SAVED_SCAN_WINDOW) {
                    scanner.scanDistinct()
                        .mapNotNull { radios -> radios.firstOrNull { it.identifier == saved.identifier } }
                        .first()
                }
            }.getOrNull()

            if (found == null) {
                error.value = "${saved.name} did not answer. It may be off or out of range."
            } else {
                session.connect(found)
            }
        }
    }

    fun setRole(saved: SavedRadio, role: NodeRole) =
        savedRadios.assign(saved.identifier, saved.name, role)

    /**
     * Rings one radio so it can be told from the others on the desk.
     *
     * Nothing comes back to say it sounded, so the notice says what silence
     * means rather than claiming success.
     */
    fun buzz(saved: SavedRadio) {
        error.value = null
        val nodeNum = saved.nodeNum
        if (nodeNum == null) {
            say("Connect ${saved.name} once so Firepit learns which node it is.")
            return
        }
        if (link.state.value !is LinkState.Ready) {
            say("Connect a radio first — a buzz travels over the mesh.")
            return
        }
        viewModelScope.launch {
            say("Buzzing ${saved.name}…", transient = false)
            runCatching { alerts.buzz(nodeNum) }.fold(
                onSuccess = { result -> say(describe(result, saved.name)) },
                onFailure = { cause -> error.value = cause.message ?: "Could not buzz ${saved.name}" },
            )
        }
    }

    private fun describe(result: BuzzResult, name: String): String = when (result) {
        BuzzResult.Delivered ->
            "$name took the buzz. Silence means its buzzer alert is off, not that it is missing."

        BuzzResult.ReachedMesh ->
            "The mesh carried the buzz, but $name never confirmed it. It may be out of range."

        BuzzResult.NoAnswer ->
            "$name did not answer. It may be off or out of range."

        BuzzResult.NoKey ->
            "$name has not introduced itself yet, so there is no private way to reach it. " +
                "Wait for it to appear on the mesh, then try again."

        is BuzzResult.Refused -> when (result.reason) {
            Routing.Error.NO_CHANNEL ->
                "$name is not on this phone's primary channel, so it cannot be reached. " +
                    "Connect it once and Firepit will set the channel."

            else -> "$name refused the buzz: ${result.reason.name.lowercase().replace('_', ' ')}."
        }
    }

    /** Said once and then forgotten, so a stale line never describes the current state. */
    private fun say(message: String, transient: Boolean = true) {
        notice.value = message
        noticeJob?.cancel()
        if (!transient) return
        noticeJob = viewModelScope.launch {
            delay(NOTICE_LIFETIME)
            notice.value = null
        }
    }

    /**
     * Stops administering [saved]. When it is the radio connected now and
     * [takeRoomsOff] is set, Firepit's rooms come off it first and its own
     * primary channel goes back, so whoever has it next holds none of the
     * rooms' channel keys. If that fails nothing is forgotten, so it can be
     * tried again.
     */
    fun forget(saved: SavedRadio, takeRoomsOff: Boolean) {
        error.value = null
        viewModelScope.launch {
            val connectedHere = uiState.value.connectedTo == saved.identifier
            if (connectedHere && takeRoomsOff) {
                val removed = runCatching { rooms.removeFirepitFromRadio() }
                    .onFailure { cause -> error.value = cause.message ?: "Could not take the rooms off this radio" }
                if (removed.isFailure) return@launch
            }
            if (connectedHere) session.disconnect()
            session.forget(saved)
        }
    }

    /**
     * Moves every room [saved] is in to new keys without it, for a radio that
     * is lost or in somebody else's hands. Needs another radio connected that
     * carries those rooms.
     */
    fun removeFromRooms(saved: SavedRadio) {
        val nodeNum = saved.nodeNum ?: return
        error.value = null
        viewModelScope.launch {
            runCatching { rooms.removeFromAllRooms(nodeNum) }.fold(
                onSuccess = { count ->
                    say(
                        if (count == 0) {
                            "${saved.name} isn't in any room this radio carries."
                        } else {
                            "${saved.name} was removed from $count room${if (count == 1) "" else "s"}. " +
                                "It gets none of their new keys."
                        },
                        transient = false,
                    )
                },
                onFailure = { cause -> error.value = cause.message ?: "Could not remove it from your rooms" },
            )
        }
    }

    /** Fixes one of [RadioUiState.risks], writing back the radio's own section with only that changed. */
    fun fixRisk(risk: RadioRisk) {
        val snapshot = (link.state.value as? LinkState.Ready)?.snapshot ?: return
        error.value = null
        viewModelScope.launch {
            runCatching {
                when (risk) {
                    RadioRisk.BLUETOOTH_OPEN, RadioRisk.BLUETOOTH_DEFAULT_PIN -> {
                        val current = checkNotNull(snapshot.bluetooth) { "The radio did not report its Bluetooth settings" }
                        val pin = RadioSecurityCheck.newPin()
                        // Shown before the write: the radio restarts, and the
                        // phone asks for this the next time it connects.
                        newPin.value = pin
                        admin.setBluetoothConfig(RadioSecurityCheck.withPin(current, pin))
                    }

                    RadioRisk.REMOTE_ADMIN_KEY, RadioRisk.LEGACY_ADMIN_CHANNEL, RadioRisk.DEBUG_LOG -> {
                        val current = checkNotNull(snapshot.security) { "The radio did not report its security settings" }
                        // The section carries the radio's own key pair. Without
                        // it the write would give the node a new identity.
                        check(current.private_key.size == TrustRules.RADIO_KEY_SIZE) {
                            "The radio did not report its own key, so its security settings can't be rewritten safely"
                        }
                        admin.setSecurityConfig(RadioSecurityCheck.withoutRemoteAccess(current))
                    }

                    RadioRisk.MQTT_UPLINK, RadioRisk.MQTT_MAP_REPORT -> {
                        val current = checkNotNull(snapshot.mqtt) { "The radio did not report its MQTT settings" }
                        admin.setMqttConfig(RadioSecurityCheck.withoutMqtt(current))
                    }

                    RadioRisk.MANAGED -> Unit
                }
            }.fold(
                onSuccess = { say("Sent to the radio. It restarts to apply the change.") },
                onFailure = { cause ->
                    newPin.value = null
                    error.value = cause.message ?: "Could not change the radio"
                },
            )
        }
    }

    fun dismissNewPin() {
        newPin.value = null
    }

    /** The radio answering is the person's own, reset or reflashed. */
    fun trustConnectedRadio() = session.trustConnectedRadio()

    fun showOnMap(saved: SavedRadio, onMap: Boolean) =
        savedRadios.showOnMap(saved.identifier, onMap)

    fun setBeaconRate(rate: BeaconRate) {
        locationSettings.setRate(rate)
        say("Saved. This no longer restarts your radio.")
    }

    fun setBeaconWhenMoved(enabled: Boolean) {
        locationSettings.setWhenMoved(enabled)
        say("Saved. This no longer restarts your radio.")
    }

    fun setRadioGps(enabled: Boolean) =
        writePosition {
            it.copy(
                gps_mode = if (enabled) {
                    Config.PositionConfig.GpsMode.ENABLED
                } else {
                    Config.PositionConfig.GpsMode.DISABLED
                },
            )
        }

    /** Built from what the radio reported, since the firmware replaces the section. */
    private fun writePosition(change: (Config.PositionConfig) -> Config.PositionConfig) {
        val position = (link.state.value as? LinkState.Ready)?.snapshot?.position ?: return
        error.value = null
        viewModelScope.launch {
            runCatching { admin.setPositionConfig(change(position)) }
                .onSuccess { say("Sent to the radio. It restarts to apply the change.") }
                .onFailure { error.value = it.message }
        }
    }

    /**
     * Changes who the connected radio relays for.
     *
     * Built from the config the radio reported, because the firmware replaces
     * the section rather than merging it.
     */
    fun setRelayReach(reach: RelayReach) {
        val device = (link.state.value as? LinkState.Ready)?.snapshot?.device ?: return
        error.value = null
        viewModelScope.launch {
            runCatching { admin.setDeviceConfig(device.copy(rebroadcast_mode = reach.mode)) }
                .exceptionOrNull()
                ?.let { error.value = it.message }
        }
    }

    /** Renames the radio itself, which is what non-Firepit apps display. */
    fun renameNode(longName: String, shortName: String) {
        error.value = null
        viewModelScope.launch {
            runCatching { owners.rename(longName, shortName) }
                .exceptionOrNull()
                ?.let { error.value = it.message }
        }
    }

    private companion object {
        /** Long enough for a radio in the room, short enough not to hang the screen. */
        val SAVED_SCAN_WINDOW = 20.seconds

        /** A scan nobody stops stops itself. */
        val SCAN_WINDOW = 60.seconds

        /** Long enough to read, short enough not to outlive what it describes. */
        val NOTICE_LIFETIME = 8.seconds
    }

    fun disconnect() {
        viewModelScope.launch { session.disconnect() }
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
            // An unnamed channel is running the region's preset, which is what
            // the radio will actually use.
            name = channel.settings?.name?.ifBlank { "Default preset" } ?: "",
            precision = channel.settings?.module_settings?.position_precision ?: 0,
            key = ChannelKey.of(channel.settings?.psk?.toByteArray()),
            keyLabel = PrimaryChannel.keyLabel(channel.settings?.psk),
        )
    },
    knownNodes = nodes.size,
)

/** Combine takes five sources at most; these travel together. */
private data class Extras(
    val tracing: Int?,
    val result: String?,
    val saved: List<SavedRadio>,
    val owner: Owner?,
    val newPin: Int?,
    val identityDoubt: String?,
    val location: com.getfirepit.core.data.LocationSettings,
)
