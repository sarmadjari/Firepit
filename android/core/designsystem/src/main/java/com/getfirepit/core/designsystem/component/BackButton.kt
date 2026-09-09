package com.getfirepit.core.designsystem.component

import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Back affordance for screens that hide the navigation bar to use the full
 * height. Without it those screens have no visible way out.
 */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier, description: String = "Back") {
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Text("\u2039", style = MaterialTheme.typography.headlineLarge)
    }
}
