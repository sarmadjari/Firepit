package com.getfirepit.core.designsystem.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

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
        Icon(
            painter = painterResource(FirepitIcons.Back),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
    }
}
