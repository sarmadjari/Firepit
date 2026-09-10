package com.getfirepit.app.settings

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The colour you picked for yourself.
 *
 * Local to this phone. Nothing carries it over the air, so other people go on
 * seeing the hue derived from your node number — which is what lets every
 * device agree on a colour without exchanging anything.
 */
@Singleton
class IdentityPreferences @Inject constructor(
    @param:ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_identity", Context.MODE_PRIVATE)

    private val _slot = MutableStateFlow(preferences.getInt(KEY_SLOT, UNSET).takeIf { it != UNSET })

    /** Null means the colour derived from the node number. */
    val slot: StateFlow<Int?> = _slot.asStateFlow()

    fun choose(slot: Int?) {
        preferences.edit {
            if (slot == null) remove(KEY_SLOT) else putInt(KEY_SLOT, slot)
        }
        _slot.value = slot
    }

    private companion object {
        const val KEY_SLOT = "identity_slot"
        const val UNSET = -1
    }
}
