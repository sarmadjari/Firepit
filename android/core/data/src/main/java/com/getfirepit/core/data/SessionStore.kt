package com.getfirepit.core.data

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The little that must outlive a radio session.
 *
 * Our own node number is stored so the map can still tell which marker is us
 * when the radio is not connected. Everything else about a node comes from the
 * mesh and is already in the database.
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_session", Context.MODE_PRIVATE)

    var myNodeNum: Int?
        get() = preferences.getInt(KEY_MY_NODE_NUM, 0).takeIf { it != 0 }
        set(value) {
            preferences.edit { putInt(KEY_MY_NODE_NUM, value ?: 0) }
        }

    /** BLE identifier of the last radio we connected to, for reconnecting without asking. */
    var lastRadioId: String?
        get() = preferences.getString(KEY_LAST_RADIO, null)
        set(value) {
            preferences.edit { putString(KEY_LAST_RADIO, value) }
        }

    private companion object {
        const val KEY_MY_NODE_NUM = "my_node_num"
        const val KEY_LAST_RADIO = "last_radio_id"
    }
}
