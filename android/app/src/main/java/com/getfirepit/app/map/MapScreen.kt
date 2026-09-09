package com.getfirepit.app.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme

/** Placeholder until Stage 6 brings MapLibre and live positions. */
@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "The map arrives in Stage 6.",
            style = MaterialTheme.typography.bodyMedium,
            color = FirepitTheme.colors.textSecondary,
            modifier = Modifier.padding(FirepitSpacing.xl),
        )
    }
}
