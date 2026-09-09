package com.getfirepit.app.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.getfirepit.core.designsystem.component.BackButton
import com.getfirepit.core.designsystem.theme.FirepitSpacing
import com.getfirepit.core.designsystem.theme.FirepitTheme

/** Placeholder until Stage 6 brings MapLibre and live positions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Map") },
                // The bottom bar is hidden here to give the map the full screen.
                navigationIcon = { BackButton(onClick = onBack) },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "The map arrives in Stage 6.",
                style = MaterialTheme.typography.bodyMedium,
                color = FirepitTheme.colors.textSecondary,
                modifier = Modifier.padding(FirepitSpacing.xl),
            )
        }
    }
}
