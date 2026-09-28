package com.getfirepit.core.data

import android.util.Log
import com.getfirepit.core.protocol.MeshPacketBuilder
import com.getfirepit.core.transport.RadioLink
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import org.meshtastic.proto.AdminMessage
import org.meshtastic.proto.Channel
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.Config
import org.meshtastic.proto.ModuleConfig
import org.meshtastic.proto.SharedContact
import org.meshtastic.proto.User

/**
 * Local admin over the phone link.
 *
 * Admin packets are addressed to our own node, so they never reach the air and
 * cost no mesh airtime. Firepit never enables remote admin: an admin channel
 * key is full device control sitting on the mesh.
 */
@Singleton
class NodeAdminClient @Inject constructor(
    private val link: RadioLink,
    private val repository: MeshRepository,
) {
    /**
     * The firmware only checks the passkey for remote senders, but it is
     * cheap, and sending it keeps the flow correct if a node is ever put in
     * managed mode.
     */
    private suspend fun sessionPasskey(): ByteString =
        request(AdminMessage(get_config_request = AdminMessage.ConfigType.SESSIONKEY_CONFIG))
            ?.session_passkey
            ?: ByteString.EMPTY

    /** Writes a channel slot. This does **not** reboot the radio. */
    suspend fun setChannel(channel: Channel) {
        send(AdminMessage(session_passkey = sessionPasskey(), set_channel = channel))
        // The radio lists its channels only during the config download, so our
        // own view has to be told; otherwise a new room stays invisible until
        // the next reconnect.
        repository.applyChannelWrite(channel)
    }

    /** Reads a slot back, so a write can be confirmed rather than assumed. */
    suspend fun getChannel(index: Int): Channel? =
        // The firmware expects index + 1 here; 0 is not a valid request.
        request(AdminMessage(get_channel_request = index + 1))?.get_channel_response

    suspend fun setOwner(user: User) {
        send(AdminMessage(session_passkey = sessionPasskey(), set_owner = user))
    }

    /**
     * Sets the radio's clock from the phone.
     *
     * The firmware records this as Net quality, below GPS, so a radio with a
     * fix keeps its own better time and only one without is corrected.
     */
    suspend fun setTime(epochSeconds: Int) {
        send(AdminMessage(session_passkey = sessionPasskey(), set_time_only = epochSeconds))
    }

    /**
     * Writes the device config back whole.
     *
     * The firmware replaces the section rather than merging it, so this takes a
     * copy of what the radio already reported: sending a config built from one
     * changed field would reset every other one to its default. Expect a reboot.
     */
    suspend fun setDeviceConfig(device: Config.DeviceConfig) {
        send(AdminMessage(session_passkey = sessionPasskey(), set_config = Config(device = device)))
    }

    /** Same whole-section replacement as [setDeviceConfig]. Expect a reboot. */
    suspend fun setPositionConfig(position: Config.PositionConfig) {
        send(AdminMessage(session_passkey = sessionPasskey(), set_config = Config(position = position)))
    }

    /** Whether the radio itself announces an arriving message. Same replacement rule. */
    suspend fun setExternalNotificationConfig(config: ModuleConfig.ExternalNotificationConfig) {
        send(
            AdminMessage(
                session_passkey = sessionPasskey(),
                set_module_config = ModuleConfig(external_notification = config),
            ),
        )
    }

    /** Favourited nodes are never evicted from the radio's bounded NodeDB. */
    suspend fun setFavorite(nodeNum: Int) {
        send(AdminMessage(session_passkey = sessionPasskey(), set_favorite_node = nodeNum))
    }

    /**
     * Puts a node and its public key into the radio's own NodeDB.
     *
     * The app remembers every node it has ever seen; the radio's database is
     * bounded and evicts. Without this, encrypting to somebody the app knows
     * about can still fail on a radio that has forgotten them.
     */
    suspend fun addContact(nodeNum: Int, user: User) {
        send(
            AdminMessage(
                session_passkey = sessionPasskey(),
                add_contact = SharedContact(node_num = nodeNum, user = user),
            ),
        )
    }

    suspend fun removeFavorite(nodeNum: Int) {
        send(AdminMessage(session_passkey = sessionPasskey(), remove_favorite_node = nodeNum))
    }

    private suspend fun send(message: AdminMessage) {
        val myNodeNum = repository.myNodeNum.value ?: error("Not connected to a radio")
        link.send(
            ToRadio(
                packet = MeshPacketBuilder.localPacket(
                    myNodeNum = myNodeNum,
                    portNum = PortNum.ADMIN_APP,
                    payload = message.encode().let(ByteString::of),
                ),
            ),
        )
    }

    /** Sends an admin request and waits for the reply carrying our packet id. */
    private suspend fun request(message: AdminMessage, timeout: Duration = REQUEST_TIMEOUT): AdminMessage? {
        val myNodeNum = repository.myNodeNum.value ?: error("Not connected to a radio")
        val packet = MeshPacketBuilder.localPacket(
            myNodeNum = myNodeNum,
            portNum = PortNum.ADMIN_APP,
            payload = message.encode().let(ByteString::of),
            wantResponse = true,
        )

        return withTimeoutOrNull(timeout) {
            coroutineScope {
                // Start listening before sending: the reply can arrive before
                // send() returns.
                val reply = async {
                    link.inbound
                        .filter { from ->
                            val data = from.packet?.decoded
                            data?.portnum == PortNum.ADMIN_APP && data.request_id == packet.id
                        }
                        .first()
                }
                link.send(ToRadio(packet = packet))
                val data = reply.await().packet?.decoded
                data?.let { runCatching { AdminMessage.ADAPTER.decode(it.payload) }.getOrNull() }
            }
        }.also { if (it == null) Log.w(TAG, "admin request timed out") }
    }

    private companion object {
        const val TAG = "FirepitAdmin"
        val REQUEST_TIMEOUT = 10.seconds
    }
}
