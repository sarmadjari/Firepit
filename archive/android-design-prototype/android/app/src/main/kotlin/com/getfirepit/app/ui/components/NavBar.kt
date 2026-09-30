package com.getfirepit.app.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.getfirepit.app.R
import com.getfirepit.app.ui.icons.FirepitIcons

enum class FirepitTab(val labelRes: Int, val icon: ImageVector) {
    Chats(R.string.tab_chats, FirepitIcons.Chats),
    Map(R.string.tab_map, FirepitIcons.Map),
    Settings(R.string.tab_settings, FirepitIcons.Settings),
}

/** Material 3 navigation bar — the Android counterpart of the iOS tab bar (UX §4.1). */
@Composable
fun FirepitNavBar(selected: FirepitTab, onSelect: (FirepitTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        FirepitTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
