package com.getfirepit.app.radio

import android.content.Context
import androidx.core.content.edit
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
    @param:ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_radios", Context.MODE_PRIVATE)

    private val _radios = MutableStateFlow(read())
    val radios: StateFlow<List<SavedRadio>> = _radios.asStateFlow()

    val personal: SavedRadio? get() = SavedRadios.personal(_radios.value)

    fun assign(identifier: String, name: String, role: NodeRole) {
        write(SavedRadios.assign(_radios.value, SavedRadio(identifier, name, role)))
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
                    .put(KEY_ROLE, radio.role.name),
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
                SavedRadio(item.getString(KEY_ID), item.getString(KEY_NAME), role)
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val KEY_RADIOS = "radios"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_ROLE = "role"
    }
}
