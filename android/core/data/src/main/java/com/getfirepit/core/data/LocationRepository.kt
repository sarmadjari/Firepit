package com.getfirepit.core.data

import android.location.Location
import android.util.Log
import com.getfirepit.core.crypto.InviteCodec
import com.getfirepit.core.model.MeshNode
import com.getfirepit.core.protocol.BeaconRate
import com.getfirepit.core.protocol.PositionPrecision
import com.getfirepit.core.protocol.PositionSharing
import com.getfirepit.core.protocol.PrecisionWrite
import com.getfirepit.core.protocol.ShareDuration
import com.getfirepit.core.protocol.TrustRules
import com.getfirepit.protocol.meshchat.MeshChatControl
import com.getfirepit.protocol.meshchat.PositionQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.proto.Config
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.ModuleSettings
import org.meshtastic.proto.Position

/** What came of asking somebody where they are. */
sealed interface PositionAnswer {

    /** A position came back, and has already been stored. */
    data object Answered : PositionAnswer

    /** The question went out; the answer will land on the map or not at all. */
    data object Asked : PositionAnswer

    /**
     * Nothing came back inside the window: they are out of range, their phone
     * is away from their radio, or they are not sharing with the room we share.
     */
    data object Silent : PositionAnswer

    /** Nothing was asked, because there is no radio to ask through. */
    data object NotConnected : PositionAnswer

    /** Not somebody we can ask: the question only travels inside a room. */
    data object NoSharedRoom : PositionAnswer
}

/**
 * Where we are, told to one room and to nobody else.
 *
 * The phone seals its own fix under the room's key and sends it at the beacon
 * rate, so the radios carrying it — and whoever holds one — read nothing. The
 * radio's own position broadcast is kept off on every channel, re-asserted on
 * every connection: it goes out under the channel key, and it would carry on
 * with the phone away, turning a share the phone ended into one that never
 * ends. The cost is that sharing pauses while the phone is away from its radio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LocationRepository @Inject constructor(
    private val mesh: MeshRepository,
    private val admin: NodeAdminClient,
    private val phoneLocation: PhoneLocationSource,
    private val rooms: RoomRepository,
    private val sharingStore: SharingStore,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * Set while the map is on screen.
     *
     * Seeing yourself on a map is a local question with no privacy consequence,
     * so it must not require opting into sharing. Sharing is the separate,
     * deliberate act below.
     */
    private val mapVisible = MutableStateFlow(false)

    fun setMapVisible(visible: Boolean) {
        mapVisible.value = visible
    }

    /** The newest fix the phone has, for answering "where are you?" without waiting on GPS. */
    @Volatile
    private var lastFix: Location? = null

    @Volatile
    private var lastShared: Location? = null

    @Volatile
    private var lastSharedAt = 0L

    @Volatile
    private var lastAnsweredAt = 0L

    /** Members whose sealed position just arrived, for whoever asked them. */
    private val positionsHeard = MutableSharedFlow<Int>(extraBufferCapacity = 16)

    /**
     * The room we are sharing with through the radio connected now, or null —
     * when nothing is shared, or when that room is not on this radio, which
     * pauses sharing rather than ending it.
     */
    fun sharingRoomId(): Int? = activeSharingRoom()

    /** When sharing stops on its own, or null when nothing is shared or nothing stops it. */
    val sharingDeadline: StateFlow<SharingDeadline?> get() = sharingStore.deadline

    /** Nodes with a known fix, newest sighting first. */
    fun observePositions(): Flow<List<MeshNode>> =
        mesh.observeNodes().map { nodes -> nodes.filter { it.hasPosition } }

    fun start() {
        scope.launch {
            // Whenever the radio reports its channels — every connect, and after
            // any channel write — make sure it broadcasts our position nowhere.
            mesh.channels.collect { channels ->
                if (channels.isEmpty()) return@collect
                val writes = PositionSharing.writesToSilence(channels)
                if (writes.isNotEmpty()) {
                    Log.w(TAG, "the radio broadcasts position on ${writes.size} channels; silencing it")
                    runCatching { silence(writes) }
                        .onFailure { cause -> Log.e(TAG, "could not stop the radio broadcasting position", cause) }
                }
                enforceDeadline()
            }
        }

        // A deadline has to pass whether or not anything else happens.
        scope.launch {
            while (true) {
                enforceDeadline()
                delay(DEADLINE_CHECK)
            }
        }

        scope.launch {
            // The GPS runs while the map is open or while sharing is live, and at
            // no other time. Nothing is sent unless a room is chosen.
            val sharing = combine(sharingStore.deadline, mesh.isConnected) { deadline, connected ->
                deadline != null && connected
            }
            combine(mapVisible, sharing) { visible, sharing -> visible to sharing }
                .distinctUntilChanged()
                .flatMapLatest { (visible, sharing) ->
                    if (visible || sharing) {
                        phoneLocation.updates().map { location -> location to sharing }
                    } else {
                        emptyFlow()
                    }
                }
                .collect { (location, sharing) ->
                    lastFix = location
                    storeOwnPosition(location)
                    if (sharing) shareIfDue(location)
                }
        }

        scope.launch {
            rooms.openedInRooms.collect { opened ->
                runCatching {
                    opened.control.position?.let { receivePosition(opened, it) }
                    opened.control.position_query?.let { answerQuery(opened) }
                }.onFailure { cause -> Log.w(TAG, "bad sealed position traffic", cause) }
            }
        }
    }

    /**
     * Records the phone's fix as our own node's position.
     *
     * Written straight to local storage rather than waiting for the radio to
     * tell us where we are: we already know, and the radio may never say.
     */
    private suspend fun storeOwnPosition(location: Location) {
        val myNodeNum = mesh.myNodeNum.value
        if (myNodeNum == null) {
            Log.w(TAG, "got a fix but no node number yet; connect the radio first")
            return
        }
        mesh.setOwnPosition(
            nodeNum = myNodeNum,
            latitudeI = (location.latitude * 1e7).toInt(),
            longitudeI = (location.longitude * 1e7).toInt(),
            altitude = location.altitude.toInt().takeIf { location.hasAltitude() },
            timeMillis = location.time,
        )
    }

    /**
     * Shares [location] when the beacon interval has passed, or sooner when
     * smart beaconing is on and we have moved far enough — the same rule the
     * radio's own broadcast followed, read from the radio's own settings.
     */
    private suspend fun shareIfDue(location: Location) {
        val roomId = activeSharingRoom() ?: return
        val now = System.currentTimeMillis()
        val config = mesh.snapshot.value?.position
        // Whatever the radio holds, not only the rates Firepit offers, within
        // reason: somebody else's app may have set it, and it was their choice.
        val interval = (config?.position_broadcast_secs?.takeIf { it > 0 } ?: BeaconRate.FIRMWARE_DEFAULT_SECONDS)
            .coerceAtLeast(MINIMUM_BEACON_SECONDS).seconds
        val sinceLast = now - lastSharedAt
        val due = lastSharedAt == 0L || sinceLast >= interval.inWholeMilliseconds ||
            (config?.position_broadcast_smart_enabled == true && movedEnough(location, config) &&
                sinceLast >= smartInterval(config).inWholeMilliseconds)
        if (due) share(roomId, location)
    }

    private fun movedEnough(location: Location, config: Config.PositionConfig): Boolean {
        val previous = lastShared ?: return true
        val minimum = config.broadcast_smart_minimum_distance.takeIf { it > 0 } ?: SMART_DISTANCE_METRES
        return previous.distanceTo(location) >= minimum
    }

    private fun smartInterval(config: Config.PositionConfig): Duration =
        config.broadcast_smart_minimum_interval_secs.takeIf { it > 0 }?.seconds ?: SMART_MINIMUM_INTERVAL

    /** Seals the fix under the room's key and sends it to the room. */
    private suspend fun share(roomId: Int, location: Location) {
        val position = Position(
            latitude_i = (location.latitude * 1e7).toInt(),
            longitude_i = (location.longitude * 1e7).toInt(),
            altitude = location.altitude.toInt().takeIf { location.hasAltitude() },
            time = (location.time / 1000L).toInt(),
            location_source = Position.LocSource.LOC_EXTERNAL,
            ground_speed = location.speed.toInt().takeIf { location.hasSpeed() },
            precision_bits = PositionPrecision.FULL,
        )
        val sent = rooms.sendSealed(roomId, MeshChatControl(version = InviteCodec.VERSION, position = position))
        if (sent) {
            lastShared = location
            lastSharedAt = System.currentTimeMillis()
            rooms.noteActivity(roomId)
        }
    }

    /**
     * A member's position, sealed by their phone. Believed only under the
     * room's current key, on the room's own slot.
     */
    private suspend fun receivePosition(opened: OpenedInRoom, position: Position) {
        if (!TrustRules.sealedPositionAcceptable(opened.sealedUnderCurrent, opened.onItsSlot)) {
            Log.w(TAG, "position from ${opened.packet.from} not sealed under room ${opened.roomId}'s current key")
            return
        }
        mesh.storeSealedPosition(opened.packet.from, position)
        positionsHeard.tryEmit(opened.packet.from)
        // Somebody sharing where they are is somebody using the room.
        rooms.noteActivity(opened.roomId)
    }

    /**
     * "Where are you?" from a member. Answered with a sealed position to the
     * room, and only while we are sharing with that room: asking is not a way
     * round somebody's choice not to share.
     */
    private suspend fun answerQuery(opened: OpenedInRoom) {
        val myNodeNum = mesh.myNodeNum.value ?: return
        val answerable = TrustRules.positionQueryAnswerable(
            sealedUnderCurrent = opened.sealedUnderCurrent && opened.onItsSlot,
            addressedToUs = opened.packet.to == myNodeNum,
            queryRoom = opened.roomId,
            sharingWithRoom = activeSharingRoom(),
        )
        if (!answerable) return
        val now = System.currentTimeMillis()
        if (now - lastAnsweredAt < ANSWER_GAP.inWholeMilliseconds) return
        val fix = lastFix?.takeIf { now - it.time < FRESH_FIX.inWholeMilliseconds } ?: return
        lastAnsweredAt = now
        share(opened.roomId, fix)
    }

    /**
     * Writes precision 0 to every channel the radio would broadcast on.
     *
     * Thrown rather than skipped when a channel cannot be read: a channel we
     * could not read is one we could not change.
     */
    private suspend fun silence(writes: List<PrecisionWrite>) {
        writes.forEach { write ->
            val channel = admin.getChannel(write.index)
                ?: throw IllegalStateException("could not read channel ${write.index} from the radio")
            val settings = channel.settings
                ?: throw IllegalStateException("channel ${write.index} came back without settings")
            admin.setChannel(
                channel.copy(
                    settings = settings.copy(
                        module_settings = (settings.module_settings ?: ModuleSettingsDefault)
                            .copy(position_precision = write.precision),
                    ),
                ),
            )
        }
        Log.i(TAG, "the radio's own position broadcast is off on ${writes.size} more channels")
    }

    /**
     * Shares with [roomId] for a chosen length of time, or stops when it is null.
     *
     * Only a Firepit room whose key this phone holds: nowhere else can a
     * position be sealed.
     */
    suspend fun shareWith(roomId: Int?, choice: ShareDuration) {
        if (roomId == null) {
            stopSharing()
            return
        }
        val room = mesh.channels.value.firstOrNull { it.id == roomId && PositionSharing.canShare(it) }
            ?: throw IllegalArgumentException("Positions can only be shared with a Firepit room")
        sharingStore.remember(room.id, choice, System.currentTimeMillis())
        // A fresh share is sent at once rather than at the next beacon.
        lastSharedAt = 0L
        lastFix?.let { shareIfDue(it) }
    }

    /** Stops sharing if its time has run out. Safe to call as often as you like. */
    private fun enforceDeadline() {
        val deadline = sharingStore.deadline.value ?: return
        if (!deadline.hasPassed(System.currentTimeMillis())) return
        Log.i(TAG, "sharing with room ${deadline.roomId} has run out; stopping")
        stopSharing()
    }

    /**
     * Stops sharing. The phone is what sends, so this takes effect at once, on
     * every radio, whether or not one is connected.
     */
    fun stopSharing() {
        sharingStore.clear()
        lastShared = null
        lastSharedAt = 0L
    }

    /** The shared room, if it is a sealed room on the radio connected now. */
    private fun activeSharingRoom(): Int? {
        val roomId = sharingStore.deadline.value?.roomId ?: return null
        return mesh.channels.value.firstOrNull { it.id == roomId && PositionSharing.canShare(it) }?.id
    }

    /**
     * Asks a member where they are without waiting to hear back.
     *
     * For asking several people at once: the answers arrive through the
     * ordinary position path and move the pins as they land.
     */
    suspend fun askForPosition(nodeNum: Int): PositionAnswer {
        if (!mesh.isConnected.value) return PositionAnswer.NotConnected
        val room = rooms.sharedRoomWith(nodeNum) ?: return PositionAnswer.NoSharedRoom
        return if (ask(room.id, nodeNum)) PositionAnswer.Asked else PositionAnswer.NotConnected
    }

    /**
     * Asks a member where they are and waits for their phone to answer.
     *
     * Sealed in a room we share, both ways, so nobody outside it learns that
     * the question was asked or what came back. Their phone answers only while
     * it is sharing with that room and near its radio; silence is the only
     * answer a mesh has for anything else, and it is reported as silence.
     */
    suspend fun requestPosition(nodeNum: Int, timeout: Duration = REPLY_TIMEOUT): PositionAnswer {
        if (!mesh.isConnected.value) return PositionAnswer.NotConnected
        val room = rooms.sharedRoomWith(nodeNum) ?: return PositionAnswer.NoSharedRoom

        var sendFailed = false
        val heard = withTimeoutOrNull(timeout) {
            coroutineScope {
                // Listening starts before sending: the answer can arrive first.
                val answer = async(start = CoroutineStart.UNDISPATCHED) { positionsHeard.first { it == nodeNum } }
                if (!ask(room.id, nodeNum)) {
                    sendFailed = true
                    answer.cancel()
                    null
                } else {
                    answer.await()
                }
            }
        }
        return when {
            sendFailed -> PositionAnswer.NotConnected
            heard != null -> PositionAnswer.Answered
            else -> {
                Log.i(TAG, "no position from $nodeNum inside $timeout")
                PositionAnswer.Silent
            }
        }
    }

    private suspend fun ask(roomId: Int, nodeNum: Int): Boolean =
        rooms.sendSealed(
            roomId,
            MeshChatControl(version = InviteCodec.VERSION, position_query = PositionQuery()),
            to = nodeNum,
            priority = MeshPacket.Priority.RELIABLE,
        )

    private companion object {
        const val TAG = "FirepitLocation"

        /** Generous: the question crosses the mesh, and so does the answer. */
        val REPLY_TIMEOUT = 60.seconds

        /** Fine-grained enough that "for 1 hour" is not visibly a lie. */
        val DEADLINE_CHECK = 30.seconds

        /** Shorter than this is a flood on a shared channel, whatever the radio says. */
        const val MINIMUM_BEACON_SECONDS = 30

        /** The firmware's own smart-beacon defaults, used when the radio leaves them at zero. */
        const val SMART_DISTANCE_METRES = 100
        val SMART_MINIMUM_INTERVAL = 30.seconds

        /** One answer to many askers at once is enough. */
        val ANSWER_GAP = 1.minutes

        /** Older than this, a fix is not "where I am now". */
        val FRESH_FIX = 5.minutes

        val ModuleSettingsDefault = ModuleSettings()
    }
}
