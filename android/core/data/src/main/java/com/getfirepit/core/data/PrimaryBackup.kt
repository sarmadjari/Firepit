package com.getfirepit.core.data

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.meshtastic.proto.Channel

/**
 * What slot 0 looked like before Firepit took it over.
 *
 * Taking a radio's primary channel is presented as a choice, so it has to be
 * one: without the settings it replaced, "keep it public" could only ever mean
 * "leave it as Firepit left it", which is not the same thing and not what the
 * words say.
 *
 * Held wrapped, because a primary channel carries the pre-shared key of
 * whatever mesh the radio was on. That key is the owner's, not ours.
 */
@Singleton
class PrimaryBackup @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences =
        context.getSharedPreferences("firepit_primary_backup", Context.MODE_PRIVATE)

    /** Kept per node: one phone administers more than one radio. */
    fun saved(nodeNum: Int): Channel? {
        val stored = preferences.getString(nodeNum.toString(), null) ?: return null
        val plain = KeystoreWrapping.unwrap(ALIAS, Base64.decode(stored, Base64.NO_WRAP))
            ?: return null
        return runCatching { Channel.ADAPTER.decode(plain) }.getOrNull()
    }

    fun has(nodeNum: Int): Boolean = preferences.contains(nodeNum.toString())

    /**
     * Records the slot once and never again, so a second takeover cannot
     * overwrite the original with Firepit's own channel.
     */
    fun remember(nodeNum: Int, channel: Channel) {
        if (has(nodeNum)) return
        val wrapped = KeystoreWrapping.wrap(ALIAS, channel.encode())
        preferences.edit {
            putString(nodeNum.toString(), Base64.encodeToString(wrapped, Base64.NO_WRAP))
        }
        Log.i(TAG, "recorded the original primary of $nodeNum before taking it over")
    }

    fun forget(nodeNum: Int) {
        preferences.edit { remove(nodeNum.toString()) }
    }

    private companion object {
        const val TAG = "FirepitPrimaryBackup"
        const val ALIAS = "firepit_primary_backup_wrapping"
    }
}

/** A channel with no key and no name is the factory one, which is not worth putting back. */
internal fun Channel.carriesAKey(): Boolean =
    (settings?.psk?.size ?: 0) > 1 || settings?.name?.isNotBlank() == true
