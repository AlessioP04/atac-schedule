package io.github.alessiop04.atacschedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colore della linea: metro con i colori ufficiali delle linee, tram verde, notturni blu, bus rosso. */
fun lineColor(routeName: String, routeType: Int): Color = when {
    routeType == 1 && routeName.startsWith("MEA") -> Color(0xFFE86E1C)
    routeType == 1 && routeName.startsWith("MEB") -> Color(0xFF1C5AA8)
    routeType == 1 && routeName.startsWith("MEC") -> Color(0xFF2E9A48)
    routeType == 0 -> Color(0xFF2E7D32)
    routeName.startsWith("n") && routeName.length > 1 -> Color(0xFF1F2A5A)
    else -> Color(0xFF8C1D18)
}

/** Nome da mostrare sul "bollino": MEA -> A, MEB1 -> B1. */
fun lineLabel(routeName: String, routeType: Int): String =
    if (routeType == 1 && routeName.startsWith("ME")) routeName.removePrefix("ME") else routeName

@Composable
fun LineBadge(routeName: String, routeType: Int, modifier: Modifier = Modifier, large: Boolean = false, fixedWidth: Boolean = true) {
    Box(
        modifier
            // larghezza fissa negli elenchi (frecce e nomi restano allineati), minima nei gruppi di bollini
            .then(if (fixedWidth) Modifier.width(if (large) 72.dp else 60.dp) else Modifier.widthIn(min = 40.dp))
            .background(lineColor(routeName, routeType), RoundedCornerShape(8.dp))
            .padding(horizontal = if (fixedWidth) 4.dp else 7.dp, vertical = if (large) 6.dp else 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val label = lineLabel(routeName, routeType)
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (if (large) 20 else 16).let { if (label.length > 4) it * 0.8f else it.toFloat() }.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** "Spinaceto → Eur": un solo testo, così il troncamento avviene alla fine e solo se serve. */
@Composable
fun FromTo(
    from: String,
    to: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    maxLines: Int = 1,
) {
    val arrowColor = LocalContentColor.current.copy(alpha = 0.55f)
    Text(
        buildAnnotatedString {
            append(from)
            withStyle(SpanStyle(color = arrowColor)) { append("  →  ") }
            append(to)
        },
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
