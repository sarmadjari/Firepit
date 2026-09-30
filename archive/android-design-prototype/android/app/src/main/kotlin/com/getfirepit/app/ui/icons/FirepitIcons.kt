package com.getfirepit.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Stroke icons on a 24×24 grid, same path data as the Figma frames (design/). Tinted by [androidx.compose.material3.Icon]
 * via LocalContentColor, so colour always follows the surrounding text token.
 */
object FirepitIcons {
    val Chats: ImageVector by lazy { stroke("chats", "M4 6a3 3 0 0 1 3-3h10a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H9l-5 4V6z") }
    val Map: ImageVector by lazy { stroke("map", "M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2V6z", "M9 4v14M15 6v14") }
    val Settings: ImageVector by lazy {
        stroke(
            "settings",
            "M4 7h9M19 7h1M4 17h1M11 17h9",
            "M18.5 7a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0",
            "M10.5 17a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0",
        )
    }
    val Plus: ImageVector by lazy { stroke("plus", "M12 5v14M5 12h14", width = 2.2f) }
    val Send: ImageVector by lazy { stroke("send", "M12 19V5M6 11l6-6 6 6", width = 2.2f) }
    val Check: ImageVector by lazy { stroke("check", "M5 12l4 4L19 7", width = 2.2f) }
    val CheckDouble: ImageVector by lazy { stroke("check-double", "M2.5 12.5l4 4L14 9", "M10 16.5l1.5 1.5L21 9", width = 2.2f) }
    val Clock: ImageVector by lazy { stroke("clock", "M20 12a8 8 0 1 1-16 0a8 8 0 0 1 16 0", "M12 8v4l3 2") }
    val Bell: ImageVector by lazy { stroke("bell", "M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15L6 16z", "M10 20a2 2 0 0 0 4 0") }
    val Pin: ImageVector by lazy { stroke("pin", "M12 21s-6-5.5-6-11a6 6 0 0 1 12 0c0 5.5-6 11-6 11z", "M14.2 10a2.2 2.2 0 1 1-4.4 0a2.2 2.2 0 0 1 4.4 0") }
    val Tent: ImageVector by lazy { stroke("tent", "M12 4l9 16H3L12 4z", "M12 11l4 9M12 11l-4 9") }
    val House: ImageVector by lazy { stroke("house", "M4 11l8-7 8 7v9H4z", "M10 20v-6h4v6") }
    val Locate: ImageVector by lazy { stroke("locate", "M21 3L10 21l-1-8-8-1L21 3z") }
    val Qr: ImageVector by lazy {
        stroke("qr", "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4z", "M14 14h2v2h-2zM18 14h2v2h-2zM16 18h2v2h-2zM18 18h2v2h-2z")
    }
    val Share: ImageVector by lazy { stroke("share", "M12 3v12M8 7l4-4 4 4M5 12v8h14v-8") }
    val Search: ImageVector by lazy { stroke("search", "M18 11a7 7 0 1 1-14 0a7 7 0 0 1 14 0", "M20 20l-3.5-3.5") }
    val More: ImageVector by lazy { stroke("more", "M12 4.5v1M12 11.5v1M12 18.5v1", width = 3f) }
    val ChevronLeft: ImageVector by lazy { stroke("chevron-left", "M15 5l-7 7 7 7") }
    val ChevronRight: ImageVector by lazy { stroke("chevron-right", "M9 5l7 7-7 7") }
    val ArrowBack: ImageVector by lazy { stroke("arrow-back", "M19 12H5M11 18l-6-6 6-6") }
    val BellOff: ImageVector by lazy { stroke("bell-off", "M6 16v-5a6 6 0 0 1 12 0v5l1.5 2h-15L6 16z", "M10 20a2 2 0 0 0 4 0", "M4 4l16 16") }
    val Download: ImageVector by lazy { stroke("download", "M12 4v11M7 10l5 5 5-5M4 19h16") }
    val Copy: ImageVector by lazy { stroke("copy", "M9 9h11v11H9z", "M5 15V6a2 2 0 0 1 2-2h9") }
    val Info: ImageVector by lazy { stroke("info", "M21 12a9 9 0 1 1-18 0a9 9 0 0 1 18 0", "M12 11v5M12 8h.01", width = 2.2f) }
    val ChevronDown: ImageVector by lazy { stroke("chevron-down", "M6 9l6 6 6-6") }
    val Camera: ImageVector by lazy { stroke("camera", "M4 8h3l2-3h6l2 3h3v11H4z", "M15.5 13a3.5 3.5 0 1 1-7 0a3.5 3.5 0 0 1 7 0") }
    val Warning: ImageVector by lazy { stroke("warning", "M12 3l10 18H2L12 3z", "M12 10v4M12 17h.01", width = 2.2f) }
    val PinFill: ImageVector by lazy { fill("pin-fill", "M12 22s-7-6-7-12a7 7 0 0 1 14 0c0 6-7 12-7 12z") }
    val Bolt: ImageVector by lazy { fill("bolt", "M13 2L4 14h7l-1 8 9-12h-7l1-8z") }

    private fun stroke(name: String, vararg paths: String, width: Float = 2f): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach { d ->
                    addPath(
                        pathData = addPathNodes(d),
                        fill = null,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = width,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()

    private fun fill(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply { paths.forEach { d -> addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)) } }
            .build()
}
