package io.github.alessiop04.atacschedule.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Le poche icone che mancano in material-icons-core (evita la libreria "extended", molto pesante). */
object AppIcons {
    /** Quattro riquadri: vista a griglia. */
    val Grid: ImageVector by lazy {
        ImageVector.Builder("Grid", 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black)) {
            for ((x, y) in listOf(3f to 3f, 13f to 3f, 3f to 13f, 13f to 13f)) {
                moveTo(x + 1.5f, y); lineTo(x + 6.5f, y)
                arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f); lineToRelative(0f, 5f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f); lineToRelative(-5f, 0f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f); lineToRelative(0f, -5f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f); close()
            }
        }.build()
    }

    /** Due schede impilate: vista dettagliata. */
    val Cards: ImageVector by lazy {
        ImageVector.Builder("Cards", 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black)) {
            for (y in listOf(4f, 13f)) {
                moveTo(4.5f, y); lineTo(19.5f, y)
                arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f); lineToRelative(0f, 4f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f); lineToRelative(-15f, 0f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f); lineToRelative(0f, -4f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, -1.5f); close()
            }
        }.build()
    }
}
