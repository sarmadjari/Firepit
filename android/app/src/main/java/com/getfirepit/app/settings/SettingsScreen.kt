package com.getfirepit.app.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.getfirepit.app.radio.RadioScreen

/**
 * Stage 3 keeps the radio harness here so the node can still be connected and
 * inspected. Stage 8 replaces it with the real settings tree.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    RadioScreen(modifier.fillMaxSize())
}
