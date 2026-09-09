package com.getfirepit.core.designsystem.component

import androidx.annotation.DrawableRes
import com.getfirepit.core.designsystem.R

/**
 * The app's own icon set.
 *
 * One stroke weight, one 24dp grid and no fills, so the three navigation
 * symbols read as a family. They also take the theme colour, which emoji
 * cannot.
 */
object FirepitIcons {
    @DrawableRes val Chats: Int = R.drawable.ic_chats

    @DrawableRes val Map: Int = R.drawable.ic_map

    @DrawableRes val Settings: Int = R.drawable.ic_settings
}
