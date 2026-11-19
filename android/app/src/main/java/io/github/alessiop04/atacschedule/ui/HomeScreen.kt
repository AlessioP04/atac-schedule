package io.github.alessiop04.atacschedule.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.alessiop04.atacschedule.App
import io.github.alessiop04.atacschedule.data.AppRelease
import io.github.alessiop04.atacschedule.data.DbMeta
import io.github.alessiop04.atacschedule.data.Favorite
import io.github.alessiop04.atacschedule.data.LineKey
import io.github.alessiop04.atacschedule.data.UpdateResult
import io.github.alessiop04.atacschedule.domain.Format
import io.github.alessiop04.atacschedule.domain.LineSchedule
import io.github.alessiop04.atacschedule.system.Shortcuts
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
import java.time.LocalDate
import java.time.LocalDateTime

data class FavoriteRow(val favorite: Favorite, val next: List<LocalDateTime>, val hasSchedule: Boolean)

class HomeViewModel(private val app: App) : ViewModel() {
    private val schedules = MutableStateFlow<Map<LineKey, LineSchedule>?>(null)

    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val meta: StateFlow<DbMeta?> = app.timetable.meta
    val compact: StateFlow<Boolean> = app.prefs.compactHome
    val newRelease: StateFlow<AppRelease?> = app.prefs.newRelease
    val canPin: Boolean = Shortcuts.canPin(app)

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
            if (meta == null || stale) {
                update(quiet = meta != null)
                launch { app.prefs.checkForNewRelease() }
            }
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
            if (!quiet) app.prefs.checkForNewRelease()
        }
    }

    fun setCompact(value: Boolean) = app.prefs.setCompactHome(value)
    fun rename(id: Long, from: String, to: String) = app.favorites.rename(id, from, to)
    fun move(id: Long, delta: Int) = app.favorites.move(id, delta)
    fun pin(f: Favorite) = Shortcuts.pin(app, f)
    fun remove(f: Favorite): Int {
        val index = app.favorites.items.value.indexOf(f)
        app.favorites.remove(f.id)
        return index
    }
    fun restore(f: Favorite, index: Int) = app.favorites.restore(f, index)
}

/** Azioni del menu di un preferito, comuni alle due viste. */
private class FavoriteActions(
    val onRename: () -> Unit,
    val onMove: (Int) -> Unit,
    val onPin: (() -> Unit)?,
    val onRemove: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (Long) -> Unit, onAdd: () -> Unit) {
    val app = appFromContext()
    val vm: HomeViewModel = viewModel { HomeViewModel(app) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val updating by vm.updating.collectAsStateWithLifecycle()
    val meta by vm.meta.collectAsStateWithLifecycle()
    val compact by vm.compact.collectAsStateWithLifecycle()
    val newRelease by vm.newRelease.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<Favorite?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    fun actionsFor(f: Favorite) = FavoriteActions(
        onRename = { renaming = f },
        onMove = { vm.move(f.id, it) },
        onPin = if (vm.canPin) ({ vm.pin(f) }) else null,
        onRemove = {
            val index = vm.remove(f)
            scope.launch {
                val r = snackbar.showSnackbar("Linea ${f.routeName} rimossa", actionLabel = "Annulla", withDismissAction = true)
                if (r == SnackbarResult.ActionPerformed) vm.restore(f, index)
            }
        },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Le mie fermate") },
                actions = {
                    IconButton(onClick = { vm.setCompact(!compact) }) {
                        Icon(if (compact) AppIcons.Cards else AppIcons.Grid, if (compact) "Vista dettagliata" else "Vista compatta")
                    }
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
        val contentPadding = PaddingValues(
            start = 12.dp, end = 12.dp,
            top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 88.dp,
        )
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Column(Modifier.padding(padding)) {
                if (hasNotices(meta, newRelease)) Box(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Notices(meta, newRelease, onUpdate = { vm.update() })
                }
                EmptyHome(meta == null, updating, Modifier)
            }
            compact -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasNotices(meta, newRelease)) item(key = "notices", span = { GridItemSpan(maxLineSpan) }) {
                    Notices(meta, newRelease, onUpdate = { vm.update() })
                }
                items(list, key = { it.favorite.id }) { row ->
                    CompactTile(
                        row = row,
                        isFirst = row == list.first(),
                        isLast = row == list.last(),
                        onClick = { onOpen(row.favorite.id) },
                        actions = actionsFor(row.favorite),
                        modifier = Modifier.animateItem(),
                    )
                }
                item(key = "validity", span = { GridItemSpan(maxLineSpan) }) { Validity(meta) }
            }
            else -> LazyColumn(
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (hasNotices(meta, newRelease)) item(key = "notices") { Notices(meta, newRelease, onUpdate = { vm.update() }) }
                items(list, key = { it.favorite.id }) { row ->
                    FavoriteCard(
                        row = row,
                        isFirst = row == list.first(),
                        isLast = row == list.last(),
                        onClick = { onOpen(row.favorite.id) },
                        actions = actionsFor(row.favorite),
                        modifier = Modifier.animateItem(),
                    )
                }
                item(key = "validity") { Validity(meta) }
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

private fun dataExpiring(meta: DbMeta?) = meta != null && meta.validTo.isBefore(LocalDate.now(App.ROME).plusDays(3))

private fun hasNotices(meta: DbMeta?, newRelease: AppRelease?) = newRelease != null || dataExpiring(meta)

/** Avvisi in cima: nuova versione dell'app, orari in scadenza. Niente se va tutto bene. */
@Composable
private fun Notices(meta: DbMeta?, newRelease: AppRelease?, onUpdate: () -> Unit) {
    val context = LocalContext.current
    val today = LocalDate.now(App.ROME)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        newRelease?.let { r ->
            Notice(
                text = "È disponibile la versione ${r.version} dell'app.",
                action = "Scarica",
                onAction = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl))) },
            )
        }
        if (meta != null && dataExpiring(meta)) {
            Notice(
                text = if (meta.validTo.isBefore(today)) "Gli orari sono scaduti il ${Format.day(meta.validTo)}."
                else "Gli orari sono validi solo fino al ${Format.day(meta.validTo)}.",
                action = "Aggiorna",
                onAction = onUpdate,
                warning = true,
            )
        }
    }
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit, warning: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun Validity(meta: DbMeta?) {
    meta ?: return
    Text(
        "Orari validi fino al ${Format.day(meta.validTo)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
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
private fun FavoriteMenu(expanded: Boolean, onDismiss: () -> Unit, isFirst: Boolean, isLast: Boolean, actions: FavoriteActions) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Rinomina") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
            onClick = { onDismiss(); actions.onRename() })
        if (!isFirst) DropdownMenuItem(text = { Text("Sposta prima") }, leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, null) },
            onClick = { onDismiss(); actions.onMove(-1) })
        if (!isLast) DropdownMenuItem(text = { Text("Sposta dopo") }, leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null) },
            onClick = { onDismiss(); actions.onMove(1) })
        actions.onPin?.let { pin ->
            DropdownMenuItem(text = { Text("Aggiungi a Home") }, leadingIcon = { Icon(Icons.Default.Home, null) },
                onClick = { onDismiss(); pin() })
        }
        DropdownMenuItem(text = { Text("Rimuovi") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
            onClick = { onDismiss(); actions.onRemove() })
    }
}

@Composable
private fun FavoriteCard(
    row: FavoriteRow,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    actions: FavoriteActions,
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
                    FavoriteMenu(menu, { menu = false }, isFirst, isLast, actions)
                }
            }
            Spacer(Modifier.height(8.dp))
            NextDepartures(row)
        }
    }
}

/**
 * Vista compatta: due tragitti per riga, il nome "da → a" in evidenza, la linea e il primo passaggio.
 * Tocco = orari, pressione lunga = menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactTile(
    row: FavoriteRow,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    actions: FavoriteActions,
    modifier: Modifier = Modifier,
) {
    val f = row.favorite
    var menu by remember { mutableStateOf(false) }
    val now = LocalDateTime.now(App.ROME)
    Box(modifier) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .combinedClickable(onClick = onClick, onLongClick = { menu = true }, onLongClickLabel = "Opzioni")
                    .padding(12.dp)
                    .heightIn(min = 92.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LineBadge(f.routeName, f.routeType)
                    Spacer(Modifier.weight(1f))
                    val first = row.next.firstOrNull()
                    if (first != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(timeLabel(first, now), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(
                                Format.wait(Duration.between(now.withSecond(0).withNano(0), first).toMinutes()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                            )
                        }
                    } else {
                        Text("—", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                // il nome scelto dall'utente è l'informazione principale della scheda
                val nameStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 24.sp)
                Text(f.fromLabel, style = nameStyle, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("→ ", style = nameStyle, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                    Text(f.toLabel, style = nameStyle, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            FavoriteMenu(menu, { menu = false }, isFirst, isLast, actions)
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
