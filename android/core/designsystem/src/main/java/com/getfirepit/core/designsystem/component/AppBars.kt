package com.getfirepit.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.getfirepit.core.designsystem.theme.FirepitSpacing

/**
 * The bar on a top-level tab: the name of the place, at headline size.
 *
 * Takes an avatar, a badge, a second line and a way back, because a chat header
 * is the same shape as a tab header wearing all of them at once. Without these
 * slots a screen that needed them had to rebuild the bar, and two rebuilt bars
 * drift apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirepitTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    badge: @Composable (() -> Unit)? = null,
    subtitle: @Composable (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.m),
                modifier = Modifier.fillMaxWidth(),
            ) {
                leading?.invoke()
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FirepitSpacing.xs),
                    ) {
                        Text(
                            text = title,
                            // Smaller beside an avatar: the pair carries the weight
                            // that the word alone had to carry by itself.
                            style = if (leading == null) {
                                MaterialTheme.typography.headlineLarge
                            } else {
                                MaterialTheme.typography.titleLarge
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        badge?.invoke()
                    }
                    subtitle?.invoke()
                }
            }
        },
        navigationIcon = { onBack?.let { back -> BackButton(onClick = back) } },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

/** The bar on a screen you can leave: smaller title, and always a way back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirepitDetailBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = { BackButton(onClick = onBack) },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}
