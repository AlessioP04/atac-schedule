package io.github.alessiop04.atacschedule.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.alessiop04.atacschedule.App
import io.github.alessiop04.atacschedule.data.Favorite
import io.github.alessiop04.atacschedule.data.LineAtStop
import io.github.alessiop04.atacschedule.data.LineKey
import io.github.alessiop04.atacschedule.data.StopResult
import io.github.alessiop04.atacschedule.domain.Format
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** Esito della ricerca: null = nessun database disponibile. */
data class SearchState(val query: String, val results: List<StopResult>?)

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AddViewModel(private val app: App) : ViewModel() {
    val query = MutableStateFlow("")

    val search: StateFlow<SearchState> = query
        .debounce(200)
        .mapLatest { q ->
            if (q.isBlank()) SearchState(q, emptyList())
            else SearchState(q, app.timetable.query { it.searchStops(q) })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchState("", emptyList()))

    private val _stop = MutableStateFlow<StopResult?>(null)
    val stop: StateFlow<StopResult?> = _stop.asStateFlow()

    private val _selected = MutableStateFlow<Set<LineKey>>(emptySet())
    val selected: StateFlow<Set<LineKey>> = _selected.asStateFlow()

    val saved: StateFlow<Set<LineKey>> = app.favorites.items
        .map { list -> list.mapTo(HashSet()) { it.key } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun select(stop: StopResult?) {
        _stop.value = stop
        _selected.value = emptySet()
    }

    fun toggle(key: LineKey) {
        _selected.value = _selected.value.let { if (key in it) it - key else it + key }
    }

    fun selectedLines(): List<LineAtStop> = _stop.value?.lines.orEmpty().filter { it.key in _selected.value }

    fun save(stop: StopResult, entries: List<Pair<LineAtStop, NameEntry>>) {
        app.favorites.add(entries.map { (line, names) ->
            Favorite(0, line.key, line.routeName, line.routeType, stop.stop.name, names.from.trim(), names.to.trim())
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val app = appFromContext()
    val vm: AddViewModel = viewModel { AddViewModel(app) }
    val query by vm.query.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val stop by vm.stop.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf<List<LineAtStop>?>(null) }

    BackHandler(enabled = stop != null) { vm.select(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val s = stop
                    if (s == null) Text("Cerca fermata")
                    else Column {
                        Text(Format.prettyName(s.stop.name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Fermata ${s.stop.id} · scegli le linee", style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (stop != null) vm.select(null) else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro")
                    }
                },
            )
        },
        bottomBar = {
            if (stop != null) Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = { naming = vm.selectedLines() },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(
                        when (selected.size) {
                            0 -> "Seleziona le linee da salvare"
                            1 -> "Salva 1 linea"
                            else -> "Salva ${selected.size} linee"
                        }
                    )
                }
            }
        },
    ) { padding ->
        val s = stop
        if (s == null) SearchContent(query, search, { vm.query.value = it }, vm::select, Modifier.padding(padding))
        else LinesContent(s, selected, saved, vm::toggle, Modifier.padding(padding))
    }

    naming?.let { lines ->
        val s = stop ?: return@let
        NamesDialog(
            title = if (lines.size == 1) "Nome della linea" else "Nomi delle linee",
            entries = lines.map {
                NameEntry(it.routeName, it.routeType, Format.prettyName(it.key.headsign), Format.prettyName(s.stop.name), Format.prettyName(it.key.headsign))
            },
            confirmLabel = "Salva",
            onDismiss = { naming = null },
            onConfirm = { names ->
                vm.save(s, lines.zip(names))
                naming = null
                onSaved()
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchContent(
    query: String,
    search: SearchState,
    onQuery: (String) -> Unit,
    onSelect: (StopResult) -> Unit,
    modifier: Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Nome della fermata o numero della palina") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, "Cancella") } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
        )
        val results = search.results
        when {
            results == null -> Hint("Orari non ancora scaricati: torna alla schermata principale e premi ⟳.")
            query.isBlank() -> Hint("Il numero della palina è scritto sul cartello della fermata. Puoi cercare anche per via, es. «marzabotto».")
            results.isEmpty() && search.query == query -> Hint("Nessuna fermata trovata per «$query».")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results, key = { it.stop.id }) { r ->
                    StopRow(r, onClick = { keyboard?.hide(); onSelect(r) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StopRow(r: StopResult, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Format.prettyName(r.stop.name), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(r.stop.id, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (r.lines.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                r.lines.distinctBy { it.routeName }.forEach { LineBadge(it.routeName, it.routeType) }
            }
            Spacer(Modifier.height(4.dp))
            // I capolinea distinguono le due fermate omonime sui lati opposti della strada.
            val heads = r.lines.map { Format.prettyName(it.key.headsign) }.distinct()
            Text(
                "verso " + heads.take(4).joinToString(", ") + if (heads.size > 4) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LinesContent(
    stop: StopResult,
    selected: Set<LineKey>,
    saved: Set<LineKey>,
    onToggle: (LineKey) -> Unit,
    modifier: Modifier,
) {
    if (stop.lines.isEmpty()) {
        Box(modifier) { Hint("Nessuna linea con partenze da questa fermata nel periodo coperto dagli orari.") }
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        items(stop.lines, key = { "${it.key.routeId}|${it.key.direction}|${it.key.headsign}" }) { line ->
            val isSaved = line.key in saved
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = !isSaved) { onToggle(line.key) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = isSaved || line.key in selected, onCheckedChange = { onToggle(line.key) }, enabled = !isSaved)
                LineBadge(line.routeName, line.routeType)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("→ " + Format.prettyName(line.key.headsign), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal)
                    if (isSaved) Text("Già tra i preferiti", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
    )
}
