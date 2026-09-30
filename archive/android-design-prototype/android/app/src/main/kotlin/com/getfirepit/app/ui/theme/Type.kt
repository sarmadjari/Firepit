package com.getfirepit.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Type ramp from docs/meshchat-ux-design.md §9.2 on the platform font (Roboto). Dynamic type is honoured by sp. */
val FirepitTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),      // large title
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),     // title
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),    // list row title
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),                                        // messages
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),                                       // secondary
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),                                       // time, status
)
