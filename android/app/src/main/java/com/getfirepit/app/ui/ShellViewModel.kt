package com.getfirepit.app.ui

import androidx.lifecycle.ViewModel
import com.getfirepit.app.settings.LayoutPreferences
import com.getfirepit.core.model.LayoutChoice
import com.getfirepit.core.model.MapSide
import com.getfirepit.core.model.PaneArrangement
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** What the shell needs to lay a wide window out the way the user chose (UX §6.11). */
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val layout: LayoutPreferences,
) : ViewModel() {

    val choice: StateFlow<LayoutChoice> = layout.choice

    fun arrange(arrangement: PaneArrangement) = layout.setArrangement(arrangement)

    fun swapSides() = layout.swapSides()

    fun setMapSide(side: MapSide) = layout.setMapSide(side)

    fun setShare(upright: Boolean, share: Float) = layout.setShare(upright, share)

    fun resetDivider() = layout.resetDivider()
}
