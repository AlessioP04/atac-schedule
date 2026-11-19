package io.github.alessiop04.atacschedule.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.alessiop04.atacschedule.App
import io.github.alessiop04.atacschedule.data.Favorite
import io.github.alessiop04.atacschedule.data.LineKey
import io.github.alessiop04.atacschedule.domain.Board
import io.github.alessiop04.atacschedule.domain.BoardBuilder
import io.github.alessiop04.atacschedule.domain.Departure
import io.github.alessiop04.atacschedule.domain.Format
import io.github.alessiop04.atacschedule.domain.LineSchedule
import io.github.alessiop04.atacschedule.domain.Period
import io.github.alessiop04.atacschedule.domain.shiftWeeks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

enum class Tab(val label: String) { WEEKDAYS("Lun–Ven"), SATURDAY("Sabato"), SUNDAY("Domenica"), DAY("Giorno") }

data class Selection(val tab: Tab, val period: Period)

@OptIn(ExperimentalCoroutinesApi::class)
class LineViewModel(private val app: App, private val id: Long) : ViewModel() {
    val favorite: StateFlow<Favorite?> = app.favorites.items.map { list -> list.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.favorites.get(id))

    private val schedule: StateFlow<LineSchedule?> = combine(
        favorite.map { it?.key }.distinctUntilChanged(), app.timetable.meta,
    ) { key, _ -> key }
        .mapLatest { key -> key?.let { k -> app.timetable.query { it.schedule(k) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun today() = LocalDate.now(App.ROME)

    // Di base i feriali; nel weekend si parte direttamente dal giorno di oggi.
    val selection = MutableStateFlow(
        when (today().dayOfWeek) {
            DayOfWeek.SATURDAY -> Selection(Tab.SATURDAY, Period.Single(today()))
            DayOfWeek.SUNDAY -> Selection(Tab.SUNDAY, Period.Single(today()))
            else -> Selection(Tab.WEEKDAYS, Period.weekdaysFrom(today()))
        }
    )

    val board: StateFlow<Pair<LineSchedule, Board>?> = combine(schedule, selection) { s, sel ->
        s?.let { it to BoardBuilder.build(it, sel.period) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val now: StateFlow<LocalDateTime> = nowFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocalDateTime.now(App.ROME))

    fun selectTab(tab: Tab) {
        val t = today()
        selection.value = when (tab) {
            Tab.WEEKDAYS -> Selection(tab, Period.weekdaysFrom(t))
            Tab.SATURDAY -> Selection(tab, Period.next(t, DayOfWeek.SATURDAY))
            Tab.SUNDAY -> Selection(tab, Period.next(t, DayOfWeek.SUNDAY))
            Tab.DAY -> Selection(tab, Period.Single(t))
        }
    }

    fun showDay(date: LocalDate) {
        selection.value = Selection(Tab.DAY, Period.Single(date))
    }

    /** Frecce ‹ ›: di una settimana, o di un giorno nella vista "Giorno". */
    fun shift(delta: Int) {
        val sel = selection.value
        val p = sel.period
        selection.value = sel.copy(
            period = if (sel.tab == Tab.DAY && p is Period.Single) Period.Single(p.date.plusDays(delta.toLong())) else p.shiftWeeks(delta.toLong())
        )
    }

    fun rename(from: String, to: String) = app.favorites.rename(id, from, to)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineScreen(id: Long, onBack: () -> Unit, onShowRoute: (LineKey) -> Unit) {
    val app = appFromContext()
    val vm: LineViewModel = viewModel(key = "line-$id") { LineViewModel(app, id) }
    val fav by vm.favorite.collectAsStateWithLifecycle()
    val data by vm.board.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }

    val f = fav
    if (f == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(f.routeName, f.routeType)
                        Spacer(Modifier.width(10.dp))
                        FromTo(f.fromLabel, f.toLabel, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                actions = {
                    IconButton(onClick = { onShowRoute(f.key) }) { Icon(AppIcons.Map, "Percorso sulla mappa") }
                    IconButton(onClick = { renaming = true }) { Icon(Icons.Default.Edit, "Rinomina") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Fermata ${f.key.stopId} · ${Format.prettyName(f.stopName)} · capolinea ${Format.prettyName(f.key.headsign)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                val tabs = Tab.entries.filter { it != Tab.DAY || selection.tab == Tab.DAY }
                tabs.forEachIndexed { i, tab ->
                    SegmentedButton(
                        selected = selection.tab == tab,
                        onClick = { vm.selectTab(tab) },
                        shape = SegmentedButtonDefaults.itemShape(i, tabs.size),
                        icon = {},
                    ) { Text(tab.label, maxLines = 1) }
                }
            }
            PeriodBar(selection, onShift = vm::shift, onPick = { picking = true })
            HorizontalDivider()

            val d = data
            if (d == null) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            } else {
                BoardContent(d.second, now, onSpecialDay = vm::showDay)
            }
        }
    }

    if (renaming) {
        NamesDialog(
            title = "Rinomina",
            entries = listOf(NameEntry(f.routeName, f.routeType, "", Format.prettyName(f.stopName), Format.prettyName(f.key.headsign), f.from, f.to)),
            confirmLabel = "Salva",
            onDismiss = { renaming = false },
            onConfirm = { vm.rename(it[0].from, it[0].to); renaming = false },
        )
    }
    if (picking) {
        data?.first?.let { s -> DayPicker(s, onDismiss = { picking = false }, onPick = { picking = false; vm.showDay(it) }) }
    }
}

@Composable
private fun PeriodBar(selection: Selection, onShift: (Int) -> Unit, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onShift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Precedente") }
        Text(
            Format.range(selection.period.days),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onShift(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Successivo") }
        IconButton(onClick = onPick) { Icon(Icons.Default.DateRange, "Scegli un giorno") }
    }
}

@Composable
private fun BoardContent(board: Board, now: LocalDateTime, onSpecialDay: (LocalDate) -> Unit) {
    val today = now.toLocalDate()
    val nowMinute = now.hour * 60 + now.minute
    val includesToday = today in board.days
    // Prossimo passaggio: il primo da adesso in poi che avviene davvero oggi.
    val next = if (includesToday) board.departures.firstOrNull { it.minute >= nowMinute && today in it.days } else null
    val byHour = board.departures.groupBy { it.minute / 60 }.toList()
    val listState = rememberLazyListState()

    LaunchedEffect(board, next?.minute) {
        val hour = next?.minute?.div(60) ?: return@LaunchedEffect
        val index = byHour.indexOfFirst { it.first == hour }
        // l'ora precedente resta visibile in alto (la riga 0 sono le note)
        if (index > 0) listState.scrollToItem(index)
    }

    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.fillMaxSize()) {
        item(key = "notes") { Notes(board, onSpecialDay) }
        if (board.departures.isEmpty()) {
            item(key = "empty") {
                Text(
                    if (board.days.isEmpty()) "Orari non disponibili per questi giorni." else "Nessuna corsa in questi giorni.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        items(byHour, key = { it.first }) { (hour, deps) ->
            HourRow(hour, deps, board, isPast = { dep -> includesToday && dep.minute < nowMinute && today in dep.days }, next = next)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Notes(board: Board, onSpecialDay: (LocalDate) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (board.specialDays.isNotEmpty()) {
            Text("Giorni con orario diverso, da consultare a parte:", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                board.specialDays.forEach { day ->
                    AssistChip(
                        onClick = { onSpecialDay(day) },
                        label = { Text(Format.day(day)) },
                        leadingIcon = { Icon(Icons.Default.Info, null, Modifier.width(18.dp)) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    )
                }
            }
        }
        if (board.uncoveredDays.isNotEmpty()) {
            Text(
                "Orari non ancora disponibili per: " + board.uncoveredDays.joinToString(", ") { Format.day(it) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (board.hasIrregular) {
            val example = board.days.first()
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)) { append("In arancio") }
                    append(" le corse che non ci sono tutti i giorni: «${Format.weekday(example)}» = solo ${weekdayLong(example)}, " +
                        "«no ${Format.weekday(example)}» = tranne ${weekdayLong(example)}.")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun weekdayLong(d: LocalDate): String = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Format.IT)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HourRow(hour: Int, deps: List<Departure>, board: Board, isPast: (Departure) -> Boolean, next: Departure?) {
    val allPast = deps.all(isPast)
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(
            "%02d".format(hour),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(40.dp).padding(top = 4.dp).alpha(if (allPast) 0.4f else 1f),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f),
        ) {
            deps.forEach { dep -> MinuteCell(dep, board, past = isPast(dep), isNext = dep == next) }
        }
    }
}

@Composable
private fun MinuteCell(dep: Departure, board: Board, past: Boolean, isNext: Boolean) {
    val regular = board.isRegular(dep)
    val color = when {
        isNext -> MaterialTheme.colorScheme.onPrimary
        regular -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.tertiary
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .widthIn(min = 44.dp)
            .alpha(if (past) 0.38f else 1f)
            .background(if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            "%02d".format(dep.minute % 60),
            fontSize = 20.sp,
            fontWeight = if (isNext || !regular) FontWeight.Bold else FontWeight.Normal,
            color = color,
        )
        if (!regular) {
            Text(Format.exceptionLabel(dep.days, board.days), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPicker(schedule: LineSchedule, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    fun LocalDate.millis() = atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = LocalDate.now(App.ROME).millis(),
        yearRange = schedule.validFrom.year..schedule.validTo.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) =
                schedule.isCovered(Instant.ofEpochMilli(utcTimeMillis).atOffset(ZoneOffset.UTC).toLocalDate())
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("Mostra") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    ) { DatePicker(state, title = { Text("Orari di un giorno", Modifier.padding(start = 24.dp, top = 16.dp)) }) }
}
