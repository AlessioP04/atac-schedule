package io.github.alessiop04.atacschedule.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Una riga del dialogo: la linea e i nomi "da"/"a" (vuoti = nomi predefiniti, mostrati come suggerimento). */
data class NameEntry(
    val routeName: String,
    val routeType: Int,
    val headsign: String,
    val defaultFrom: String,
    val defaultTo: String,
    val from: String = "",
    val to: String = "",
)

/**
 * Chiede un nome breve "da → a" per una o più linee, es. "Spinaceto → Eur".
 * I campi sono facoltativi: se restano vuoti si usano fermata e capolinea.
 */
@Composable
fun NamesDialog(
    title: String,
    entries: List<NameEntry>,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (List<NameEntry>) -> Unit,
) {
    val state = remember(entries) { mutableStateListOf(*entries.toTypedArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Facoltativo: un nome breve per riconoscere subito il tragitto, es. «Spinaceto → Eur». " +
                    "Se lasci vuoto, restano i nomi in grigio.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.forEachIndexed { i, e ->
                    if (i > 0) HorizontalDivider(Modifier.padding(top = 12.dp))
                    Spacer(Modifier.height(12.dp))
                    if (entries.size > 1 || entries[0].headsign.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LineBadge(e.routeName, e.routeType)
                            Text("→ ${e.headsign}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Nessuna etichetta: il suggerimento grigio mostra il nome usato se il campo resta vuoto.
                        OutlinedTextField(
                            value = e.from,
                            onValueChange = { state[i] = e.copy(from = it) },
                            placeholder = { Text(e.defaultFrom, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.padding(horizontal = 6.dp))
                        OutlinedTextField(
                            value = e.to,
                            onValueChange = { state[i] = e.copy(to = it) },
                            placeholder = { Text(e.defaultTo, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = if (i == state.lastIndex) ImeAction.Done else ImeAction.Next,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(state.toList()) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
        modifier = Modifier.fillMaxWidth(),
        // un tocco fuori dal dialogo non deve far perdere i nomi già scritti
        properties = DialogProperties(dismissOnClickOutside = false),
    )
}
