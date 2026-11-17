package io.github.alessiop04.atacschedule.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.alessiop04.atacschedule.App
import io.github.alessiop04.atacschedule.data.DbMeta
import io.github.alessiop04.atacschedule.data.Favorite
import io.github.alessiop04.atacschedule.data.LineKey
import io.github.alessiop04.atacschedule.data.UpdateResult
import io.github.alessiop04.atacschedule.domain.Format
import io.github.alessiop04.atacschedule.domain.LineSchedule
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

data class FavoriteRow(val favorite: Favorite, val next: List<LocalDateTime>, val hasSchedule: Boolean)

class HomeViewModel(private val app: App) : ViewModel() {
    private val schedules = MutableStateFlow<Map<LineKey, LineSchedule>?>(null)

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val meta: StateFlow<DbMeta?> = app.timetable.meta

    val rows: StateFlow<List<FavoriteRow>?> = combine(app.favorites.items, schedules, nowFlow()) { favs, sched, now ->
        if (sched == null) return@combine null
        favs.map { f ->
            val s = sched[f.key]
            FavoriteRow(f, s?.upcoming(now, 4).orEmpty(), s != null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            combine(app.favorites.items, app.timetable.meta) { f, _ -> f }.collectLatest { favs ->
                schedules.value = app.timetable.query { db -> favs.associate { it.key to db.schedule(it.key) } } ?: emptyMap()
            }
        }
        viewModelScope.launch {
            // Primo avvio senza database, o ultimo controllo vecchio: aggiorna subito (in silenzio se va tutto bene).
            val meta = app.timetable.query { it.meta }
            val stale = System.currentTimeMillis() - app.timetable.lastCheckMillis > Duration.ofHours(12).toMillis()
            if (meta == null || stale) update(quiet = meta != null)
        }
    }

    fun update(quiet: Boolean = false) {
        if (_updating.value) return
        _updating.value = true
        viewModelScope.launch {
            val msg = when (val r = app.timetable.update()) {
                UpdateResult.UpToDate -> "Orari già aggiornati".takeUnless { quiet }
                is UpdateResult.Updated -> "Orari aggiornati: validi fino al ${Format.day(r.meta.validTo)}"
                UpdateResult.AppTooOld -> "Sono disponibili orari nuovi, ma serve una versione più recente dell'app"
                is UpdateResult.Failed -> "Aggiornamento non riuscito (${r.reason})".takeUnless { quiet && meta.value != null }
            }
            _updating.value = false
            msg?.let { messages.tryEmit(it) }
        }
    }

    fun rename(id: Long, from: String, to: String) = app.favorites.rename(id, from, to)
    fun move(id: Long, delta: Int) = app.favorites.move(id, delta)
    fun remove(f: Favorite): Int {
        val index = app.favorites.items.value.indexOf(f)
        app.favorites.remove(f.id)
        return index
    }
    fun restore(f: Favorite, index: Int) = app.favorites.restore(f, index)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    val app = appFromContext()
    val vm: HomeViewModel = viewModel { HomeViewModel(app) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val updating by vm.updating.collectAsStateWithLifecycle()
    val meta by vm.meta.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<Favorite?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Le mie fermate") },
                actions = {
                    if (updating) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { vm.update() }) { Icon(Icons.Default.Refresh, "Aggiorna orari") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Aggiungi") })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = rows
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyHome(meta == null, updating, Modifier.padding(padding))
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 88.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.favorite.id }) { row ->
                    FavoriteCard(
                        row = row,
                        isFirst = row == list.first(),
                        isLast = row == list.last(),
                        onClick = { onOpen(row.favorite.id) },
                        onRename = { renaming = row.favorite },
                        onMove = { vm.move(row.favorite.id, it) },
                        onRemove = {
                            val index = vm.remove(row.favorite)
                            scope.launch {
                                val r = snackbar.showSnackbar("Linea ${row.favorite.routeName} rimossa", actionLabel = "Annulla", withDismissAction = true)
                                if (r == SnackbarResult.ActionPerformed) vm.restore(row.favorite, index)
                            }
                        },
                        modifier = Modifier.animateItem(),
                    )
                }
                meta?.let { m ->
                    item(key = "validity") {
                        Text(
                            "Orari validi fino al ${Format.day(m.validTo)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }

    renaming?.let { f ->
        NamesDialog(
            title = "Rinomina",
            entries = listOf(NameEntry(f.routeName, f.routeType, Format.prettyName(f.key.headsign), Format.prettyName(f.stopName), Format.prettyName(f.key.headsign), f.from, f.to)),
            confirmLabel = "Salva",
            onDismiss = { renaming = null },
            onConfirm = { vm.rename(f.id, it[0].from, it[0].to); renaming = null },
        )
    }
}

@Composable
private fun EmptyHome(noDatabase: Boolean, updating: Boolean, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (noDatabase && updating) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Scarico gli orari…", style = MaterialTheme.typography.titleMedium)
        } else {
            Text("Nessuna linea salvata", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                if (noDatabase) "Orari non ancora scaricati: serve una connessione. Premi ⟳ per riprovare."
                else "Premi «Aggiungi», cerca la tua fermata e scegli le linee che prendi.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FavoriteCard(
    row: FavoriteRow,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val f = row.favorite
    var menu by remember { mutableStateOf(false) }
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(f.routeName, f.routeType)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    FromTo(f.fromLabel, f.toLabel, maxLines = 2)
                    Text(
                        "Fermata ${f.key.stopId} · ${Format.prettyName(f.stopName)}" +
                            (if (f.to.isNotBlank()) " · capolinea ${Format.prettyName(f.key.headsign)}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opzioni") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rinomina") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menu = false; onRename() })
                        if (!isFirst) DropdownMenuItem(text = { Text("Sposta su") }, leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) },
                            onClick = { menu = false; onMove(-1) })
                        if (!isLast) DropdownMenuItem(text = { Text("Sposta giù") }, leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
                            onClick = { menu = false; onMove(1) })
                        DropdownMenuItem(text = { Text("Rimuovi") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; onRemove() })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            NextDepartures(row)
        }
    }
}

@Composable
private fun NextDepartures(row: FavoriteRow) {
    val now = LocalDateTime.now(App.ROME)
    val next = row.next
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(end = 14.dp)) {
        if (next.isEmpty()) {
            Text(
                if (row.hasSchedule) "Nessuna corsa nei prossimi giorni" else "Orari non disponibili",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Row
        }
        val first = next.first()
        Text(timeLabel(first, now), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(
            Format.wait(Duration.between(now.withSecond(0).withNano(0), first).toMinutes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 3.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            next.drop(1).joinToString("   ") { timeLabel(it, now) },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
}

/** "14:05" oggi, "dom 06:10" in un altro giorno. */
private fun timeLabel(t: LocalDateTime, now: LocalDateTime): String {
    val hm = Format.time(t.hour * 60 + t.minute)
    return if (t.toLocalDate() == now.toLocalDate()) hm else "${Format.weekday(t.toLocalDate())} $hm"
}
