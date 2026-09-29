package com.getfirepit.app.radio

import android.content.Context
import androidx.core.content.edit
import com.getfirepit.core.protocol.DeviceTransport
import com.getfirepit.core.protocol.NodeRole
import com.getfirepit.core.protocol.SavedRadio
import com.getfirepit.core.protocol.SavedRadios
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The radios this phone administers, and what each is for.
 *
 * Stored as JSON rather than one key per radio: the set changes as a whole when
 * Personal moves, and a half-applied change would leave two Personals.
 */
@Singleton
class SavedRadioStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_radios", Context.MODE_PRIVATE)

    private val _radios = MutableStateFlow(read())
    val radios: StateFlow<List<SavedRadio>> = _radios.asStateFlow()

    val personal: SavedRadio? get() = SavedRadios.personal(_radios.value)

    fun assign(
        identifier: String,
        name: String,
        role: NodeRole,
        transport: DeviceTransport = DeviceTransport.BLUETOOTH,
    ) {
        val existing = _radios.value.firstOrNull { it.identifier == identifier }
        write(
            SavedRadios.assign(
                _radios.value,
                SavedRadio(
                    identifier = identifier,
                    name = name,
                    role = role,
                    transport = existing?.transport ?: transport,
                    nodeNum = existing?.nodeNum,
                    onMap = existing?.onMap != false,
                    publicKey = existing?.publicKey,
                ),
            ),
        )
    }

    /**
     * Learned once the radio says who it is, and kept for when it is away.
     *
     * Only ever filled in, never overwritten: false when the radio answering
     * at this address is not the one saved here — a different node, or the
     * same node number under a different key. Anything can answer at a
     * Bluetooth address; replacing the saved identity would make whatever
     * answered the radio this phone trusts.
     */
    fun rememberNode(identifier: String, nodeNum: Int, publicKey: String?): Boolean {
        val existing = _radios.value.firstOrNull { it.identifier == identifier } ?: return true
        val sameNode = existing.nodeNum == null || existing.nodeNum == nodeNum
        // A radio that once showed a key and now shows none is not the same
        // radio until the person says so: saying nothing is how an impostor
        // would get past a comparison.
        val sameKey = existing.publicKey == null || existing.publicKey == publicKey
        if (!sameNode || !sameKey) return false
        if (existing.nodeNum != nodeNum || (existing.publicKey == null && publicKey != null)) {
            write(SavedRadios.assign(_radios.value, existing.copy(nodeNum = nodeNum, publicKey = publicKey ?: existing.publicKey)))
        }
        return true
    }

    /** The person says the radio answering now is theirs after all, reset or reflashed. */
    fun trust(identifier: String, nodeNum: Int, publicKey: String?) {
        val existing = _radios.value.firstOrNull { it.identifier == identifier } ?: return
        write(SavedRadios.assign(_radios.value, existing.copy(nodeNum = nodeNum, publicKey = publicKey)))
    }

    fun showOnMap(identifier: String, onMap: Boolean) {
        val existing = _radios.value.firstOrNull { it.identifier == identifier } ?: return
        write(SavedRadios.assign(_radios.value, existing.copy(onMap = onMap)))
    }

    fun forget(identifier: String) {
        write(SavedRadios.forget(_radios.value, identifier))
    }

    private fun write(radios: List<SavedRadio>) {
        val array = JSONArray()
        radios.forEach { radio ->
            array.put(
                JSONObject()
                    .put(KEY_ID, radio.identifier)
                    .put(KEY_NAME, radio.name)
                    .put(KEY_ROLE, radio.role.name)
                    .put(KEY_TRANSPORT, radio.transport.name)
                    .put(KEY_NODE, radio.nodeNum ?: JSONObject.NULL)
                    .put(KEY_ON_MAP, radio.onMap)
                    .put(KEY_PUBLIC_KEY, radio.publicKey ?: JSONObject.NULL),
            )
        }
        preferences.edit { putString(KEY_RADIOS, array.toString()) }
        _radios.value = radios
    }

    private fun read(): List<SavedRadio> {
        val raw = preferences.getString(KEY_RADIOS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.getJSONObject(index)
                val role = NodeRole.entries.firstOrNull { it.name == item.getString(KEY_ROLE) }
                    ?: return@mapNotNull null
                // Devices saved before transports were named were all Bluetooth.
                val transport = DeviceTransport.entries
                    .firstOrNull { it.name == item.optString(KEY_TRANSPORT) }
                    ?: DeviceTransport.BLUETOOTH
                SavedRadio(
                    identifier = item.getString(KEY_ID),
                    name = item.getString(KEY_NAME),
                    role = role,
                    transport = transport,
                    nodeNum = item.opt(KEY_NODE)?.takeIf { it != JSONObject.NULL } as? Int,
                    onMap = item.optBoolean(KEY_ON_MAP, true),
                    publicKey = item.opt(KEY_PUBLIC_KEY)?.takeIf { it != JSONObject.NULL } as? String,
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_RADIOS = "radios"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_ROLE = "role"
        const val KEY_TRANSPORT = "transport"
        const val KEY_NODE = "node"
        const val KEY_ON_MAP = "onMap"
        const val KEY_PUBLIC_KEY = "publicKey"
    }
}
