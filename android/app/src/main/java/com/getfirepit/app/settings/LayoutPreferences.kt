package com.getfirepit.app.settings

import android.content.Context
import androidx.core.content.edit
import com.getfirepit.core.model.LayoutChoice
import com.getfirepit.core.model.MapSide
import com.getfirepit.core.model.PaneArrangement
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How a wide screen is arranged, and where the divider was left (UX §6.11.4).
 *
 * Kept on this phone only. The divider is remembered apart for upright and
 * wide windows, because a share that suits one rarely suits the other.
 */
@Singleton
class LayoutPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("firepit_layout", Context.MODE_PRIVATE)

    private val _choice = MutableStateFlow(read())
    val choice: StateFlow<LayoutChoice> = _choice.asStateFlow()

    fun setArrangement(arrangement: PaneArrangement) = update { it.copy(arrangement = arrangement) }

    fun setMapSide(side: MapSide) = update { it.copy(mapSide = side) }

    fun swapSides() = update {
        it.copy(mapSide = if (it.mapSide == MapSide.END) MapSide.START else MapSide.END)
    }

    /** Where the user let go of the divider: the chat side's share of an upright or a wide window. */
    fun setShare(upright: Boolean, share: Float) = update {
        if (upright) it.copy(uprightShare = share) else it.copy(wideShare = share)
    }

    /** Back on the fold, or the middle when there is none. */
    fun resetDivider() = update { it.copy(uprightShare = null, wideShare = null) }

    private fun update(change: (LayoutChoice) -> LayoutChoice) {
        val next = change(_choice.value)
        preferences.edit {
            putString(KEY_ARRANGEMENT, next.arrangement.name)
            putString(KEY_MAP_SIDE, next.mapSide.name)
            putFloat(KEY_UPRIGHT, next.uprightShare ?: UNSET)
            putFloat(KEY_WIDE, next.wideShare ?: UNSET)
        }
        _choice.value = next
    }

    private fun read(): LayoutChoice = LayoutChoice(
        arrangement = preferences.getString(KEY_ARRANGEMENT, null)
            ?.let { saved -> PaneArrangement.entries.firstOrNull { it.name == saved } }
            ?: PaneArrangement.CHAT_AND_MAP,
        mapSide = preferences.getString(KEY_MAP_SIDE, null)
            ?.let { saved -> MapSide.entries.firstOrNull { it.name == saved } }
            ?: MapSide.END,
        uprightShare = preferences.getFloat(KEY_UPRIGHT, UNSET).takeIf { it != UNSET },
        wideShare = preferences.getFloat(KEY_WIDE, UNSET).takeIf { it != UNSET },
    )

    private companion object {
        const val KEY_ARRANGEMENT = "arrangement"
        const val KEY_MAP_SIDE = "map_side"
        const val KEY_UPRIGHT = "upright_share"
        const val KEY_WIDE = "wide_share"
        const val UNSET = -1f
    }
}
