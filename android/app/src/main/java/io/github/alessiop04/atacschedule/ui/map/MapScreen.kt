package io.github.alessiop04.atacschedule.ui.map

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.alessiop04.atacschedule.App
import io.github.alessiop04.atacschedule.data.LineAtStop
import io.github.alessiop04.atacschedule.data.RoutePattern
import io.github.alessiop04.atacschedule.data.Stop
import io.github.alessiop04.atacschedule.data.StopResult
import io.github.alessiop04.atacschedule.domain.Format
import io.github.alessiop04.atacschedule.domain.Geo
import io.github.alessiop04.atacschedule.system.Locator
import io.github.alessiop04.atacschedule.system.Place
import io.github.alessiop04.atacschedule.system.Places
import io.github.alessiop04.atacschedule.ui.LineBadge
import io.github.alessiop04.atacschedule.ui.appFromContext
import io.github.alessiop04.atacschedule.ui.lineColor
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Cosa mostra la mappa. */
sealed interface MapMode {
    /** Scegli una fermata (dalla schermata «Aggiungi»): tutte le fermate, ricerca, percorsi delle linee. */
    data object Pick : MapMode

    /** Il percorso di una linea, con la fermata `stopId` evidenziata. */
    data class Route(val routeId: String, val direction: Int, val headsign: String, val stopId: String?) : MapMode
}

/** Linea di cui si mostra il percorso. */
data class ShownRoute(val routeName: String, val routeType: Int, val headsign: String, val pattern: RoutePattern?)

sealed interface Camera {
    data class Point(val lat: Double, val lon: Double, val zoom: Double?, val animate: Boolean = true) : Camera
    data class Fit(val shape: DoubleArray) : Camera
}

data class MapSearch(
    val query: String = "",
    val stops: List<StopResult> = emptyList(),
    val places: List<Place>? = null, // null = ricerca luoghi non ancora fatta per questo testo
    val searchingPlaces: Boolean = false,
)

@OptIn(FlowPreview::class)
class MapViewModel(private val app: App, val mode: MapMode) : ViewModel() {
    private val _stops = MutableStateFlow<List<Stop>>(emptyList())
    val stops: StateFlow<List<Stop>> = _stops.asStateFlow()

    private val _selected = MutableStateFlow<StopResult?>(null)
    val selected: StateFlow<StopResult?> = _selected.asStateFlow()

    private val _route = MutableStateFlow<ShownRoute?>(null)
    val route: StateFlow<ShownRoute?> = _route.asStateFlow()

    /** La linea (della fermata scelta) di cui si sta guardando il percorso. */
    private val _previewLine = MutableStateFlow<LineAtStop?>(null)
    val previewLine: StateFlow<LineAtStop?> = _previewLine.asStateFlow()

    private val _search = MutableStateFlow(MapSearch())
    val search: StateFlow<MapSearch> = _search.asStateFlow()
    private val queryFlow = MutableStateFlow("")

    val camera = MutableSharedFlow<Camera>(replay = 1, extraBufferCapacity = 4)
    private var routeJob: Job? = null

    init {
        viewModelScope.launch {
            when (mode) {
                MapMode.Pick -> {
                    _stops.value = app.timetable.query { it.allStops() }.orEmpty()
                    // si parte dalla prima fermata salvata (di solito vicino a casa), altrimenti dal centro
                    val first = app.favorites.items.value.firstOrNull()?.key?.stopId
                    val start = first?.let { id -> _stops.value.firstOrNull { it.id == id } }
                    camera.emit(
                        if (start != null) Camera.Point(start.lat, start.lon, 16.0, animate = false)
                        else Camera.Point(ROME_CENTER.latitude, ROME_CENTER.longitude, 12.0, animate = false)
                    )
                }
                is MapMode.Route -> {
                    val (name, type) = app.timetable.query { it.route(mode.routeId) } ?: (mode.routeId to 3)
                    val pattern = app.timetable.query { it.pattern(mode.routeId, mode.direction, mode.headsign) }
                    _route.value = ShownRoute(name, type, mode.headsign, pattern)
                    _selected.value = mode.stopId?.let { id -> app.timetable.query { it.stopResult(id) } }
                    if (pattern != null) camera.emit(Camera.Fit(pattern.shape))
                    else _selected.value?.stop?.let { camera.emit(Camera.Point(it.lat, it.lon, 16.0, animate = false)) }
                }
            }
        }
        viewModelScope.launch {
            queryFlow.debounce(150).collectLatest { q ->
                val results = if (q.isBlank()) emptyList() else app.timetable.query { it.searchStops(q, limit = 6) }.orEmpty()
                _search.value = _search.value.copy(stops = results)
            }
        }
    }

    fun setQuery(q: String) {
        _search.value = _search.value.copy(query = q, places = if (q == _search.value.query) _search.value.places else null)
        queryFlow.value = q
    }

    /** Vie, piazze e luoghi (OpenStreetMap): solo su conferma, mai a ogni lettera. */
    fun searchPlaces() {
        val q = _search.value.query.trim()
        if (q.length < 3 || _search.value.searchingPlaces) return
        _search.value = _search.value.copy(searchingPlaces = true)
        viewModelScope.launch {
            val places = Places.search(q)
            if (_search.value.query.trim() == q) _search.value = _search.value.copy(places = places, searchingPlaces = false)
            else _search.value = _search.value.copy(searchingPlaces = false)
        }
    }

    fun clearSearch() = setQuery("")

    private val _place = MutableStateFlow<Place?>(null)
    /** Il luogo cercato, segnato sulla mappa. */
    val place: StateFlow<Place?> = _place.asStateFlow()

    fun goTo(place: Place) {
        clearSearch()
        clearSelection() // il pannello della fermata di prima non c'entra più
        _place.value = place
        camera.tryEmit(Camera.Point(place.lat, place.lon, 17.0))
    }

    fun selectStop(stop: Stop, moveCamera: Boolean = true) {
        viewModelScope.launch {
            val result = app.timetable.query { it.stopResult(stop.id) } ?: return@launch
            _selected.value = result
            // se la linea che si sta guardando passa anche da qui, resta evidenziata
            val preview = _previewLine.value
            if (mode == MapMode.Pick && preview != null) {
                _previewLine.value = result.lines.firstOrNull { it.key.routeId == preview.key.routeId && it.key.direction == preview.key.direction }
                if (_previewLine.value == null) _route.value = null
            }
            if (moveCamera) camera.emit(Camera.Point(stop.lat, stop.lon, 17.0))
        }
    }

    fun selectStop(result: StopResult) {
        clearSearch()
        selectStop(result.stop)
    }

    fun clearSelection() {
        _selected.value = null
        _previewLine.value = null
        if (mode == MapMode.Pick) _route.value = null
    }

    /** Mostra/nasconde il percorso di una linea della fermata scelta. */
    fun togglePreview(line: LineAtStop) {
        routeJob?.cancel()
        if (_previewLine.value == line) {
            _previewLine.value = null
            _route.value = null
            return
        }
        _previewLine.value = line
        routeJob = viewModelScope.launch {
            val pattern = app.timetable.query { it.pattern(line.key.routeId, line.key.direction, line.key.headsign) }
            _route.value = ShownRoute(line.routeName, line.routeType, line.key.headsign, pattern)
            pattern?.let { camera.emit(Camera.Fit(it.shape)) }
        }
    }

    fun centerOnMe() {
        viewModelScope.launch {
            val loc = Locator.current(app) ?: return@launch
            camera.emit(Camera.Point(loc.latitude, loc.longitude, 17.0))
        }
    }
}

/**
 * Mappa delle fermate e dei percorsi.
 * @param onPickStop in modalità [MapMode.Pick]: l'utente ha scelto la fermata di cui salvare le linee
 */
@Composable
fun MapScreen(mode: MapMode, onBack: () -> Unit, onPickStop: (String) -> Unit = {}) {
    val app = appFromContext()
    val vm: MapViewModel = viewModel(key = mode.toString()) { MapViewModel(app, mode) }
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val layers = remember { MapLayers(context, dark) }
    val stops by vm.stops.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val previewLine by vm.previewLine.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val place by vm.place.collectAsStateWithLifecycle()
    var zoom by remember { mutableDoubleStateOf(layers.view.zoomLevelDouble) }
    var searchFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    val fitPadding = with(LocalDensity.current) { 56.dp.roundToPx() }

    // ciclo di vita della MapView
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> layers.onResume()
                Lifecycle.Event.ON_PAUSE -> layers.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        layers.onZoom = { zoom = it }
        if (Locator.hasPermission(context)) layers.showMyLocation()
        onDispose {
            lifecycle.removeObserver(observer)
            layers.onDestroy()
        }
    }

    LaunchedEffect(stops) { if (stops.isNotEmpty()) layers.setStops(stops) { vm.selectStop(it, moveCamera = false) } }
    LaunchedEffect(route) {
        val r = route
        layers.setRoute(r?.pattern, r?.let { lineColor(it.routeName, it.routeType).toArgb() } ?: 0) { vm.selectStop(it, moveCamera = false) }
    }
    LaunchedEffect(selected) { layers.setSelected(selected?.stop, primary) }
    LaunchedEffect(place) { layers.setPlace(place?.lat, place?.lon) }
    LaunchedEffect(Unit) {
        vm.camera.collect { c ->
            when (c) {
                is Camera.Point -> layers.moveTo(c.lat, c.lon, c.zoom, c.animate)
                is Camera.Fit -> layers.fit(c.shape, fitPadding)
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) {
            layers.showMyLocation()
            vm.centerOnMe()
        }
    }

    BackHandler(enabled = searchFocused || search.query.isNotEmpty() || (mode == MapMode.Pick && selected != null)) {
        when {
            search.query.isNotEmpty() -> vm.clearSearch()
            searchFocused -> keyboard?.hide()
            else -> vm.clearSelection()
        }
    }

    // La mappa sta sopra il pannello (non sotto): così gli inquadramenti dei percorsi restano interamente visibili.
    Column(Modifier.fillMaxSize()) {
        val sel = selected
        val r = route
        val hasPanel = (mode is MapMode.Route && r != null) || sel != null
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { layers.view }, modifier = Modifier.fillMaxSize())

            Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (mode == MapMode.Pick) {
                    SearchBar(
                        search = search,
                        onBack = onBack,
                        onQuery = vm::setQuery,
                        onSubmit = { keyboard?.hide(); vm.searchPlaces() },
                        onFocus = { searchFocused = it },
                    )
                    if (search.query.isNotBlank()) {
                        SearchResults(
                            search,
                            onStop = { keyboard?.hide(); vm.selectStop(it) },
                            onPlace = { keyboard?.hide(); vm.goTo(it) },
                            onSearchPlaces = { keyboard?.hide(); vm.searchPlaces() },
                        )
                    } else if (zoom < STOPS_MIN_ZOOM && sel == null) {
                        Hint("Avvicina la mappa per vedere le fermate")
                    }
                } else {
                    Surface(shape = CircleShape, shadowElevation = 4.dp) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                    }
                }
            }

            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(if (hasPanel) Modifier else Modifier.navigationBarsPadding()),
                horizontalAlignment = Alignment.End,
            ) {
                SmallFloatingActionButton(
                    onClick = {
                        if (Locator.hasPermission(context)) { layers.showMyLocation(); vm.centerOnMe() }
                        else permission.launch(Locator.PERMISSIONS)
                    },
                    modifier = Modifier.padding(12.dp),
                ) { Icon(Icons.Default.LocationOn, "La mia posizione") }
                Attribution()
            }
        }

        if (hasPanel) Box(Modifier.navigationBarsPadding()) {
            when {
                mode is MapMode.Route && r != null -> RoutePanel(r, highlight = sel?.stop?.id, onStop = { vm.selectStop(it) })
                sel != null -> StopPanel(
                    stop = sel,
                    previewLine = previewLine,
                    route = r,
                    onLine = vm::togglePreview,
                    onClose = vm::clearSelection,
                    onPick = { onPickStop(sel.stop.id) },
                )
            }
        }
    }
}

@Composable
private fun SearchBar(search: MapSearch, onBack: () -> Unit, onQuery: (String) -> Unit, onSubmit: () -> Unit, onFocus: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
            TextField(
                value = search.query,
                onValueChange = onQuery,
                placeholder = { Text("Fermata, via o luogo", maxLines = 1) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).onFocusChanged { onFocus(it.isFocused) },
            )
            if (search.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Clear, "Cancella") }
            else Icon(Icons.Default.Search, null, Modifier.padding(end = 16.dp))
        }
    }
}

@Composable
private fun SearchResults(search: MapSearch, onStop: (StopResult) -> Unit, onPlace: (Place) -> Unit, onSearchPlaces: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 4.dp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            if (search.stops.isNotEmpty()) item { Section("Fermate") }
            items(search.stops, key = { "s" + it.stop.id }) { r ->
                Row(
                    Modifier.fillMaxWidth().clickable { onStop(r) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(Format.prettyName(r.stop.name), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            r.lines.map { it.routeName }.distinct().joinToString(" · ").ifEmpty { "—" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(r.stop.id, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val places = search.places
            when {
                search.searchingPlaces -> item {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Cerco vie e luoghi…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                places == null -> item {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onSearchPlaces).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text("Cerca «${search.query.trim()}» tra vie e luoghi", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                places.isEmpty() -> item { Section("Nessuna via o luogo trovato") }
                else -> {
                    item { Section("Vie e luoghi") }
                    items(places, key = { "p${it.lat},${it.lon}" }) { p ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPlace(p) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Place, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (p.detail.isNotBlank()) Text(p.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.inverseSurface, modifier = Modifier.padding(top = 8.dp)) {
        Text(text, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** Pannello della fermata scelta: linee (toccandole si vede il percorso) e pulsante per salvarle. */
@Composable
private fun StopPanel(
    stop: StopResult,
    previewLine: LineAtStop?,
    route: ShownRoute?,
    onLine: (LineAtStop) -> Unit,
    onClose: () -> Unit,
    onPick: () -> Unit,
) {
    Panel {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(Format.prettyName(stop.stop.name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Fermata ${stop.stop.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Chiudi") }
        }
        if (stop.lines.isEmpty()) {
            Text("Nessuna linea parte da questa fermata.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            Text(
                if (previewLine == null) "Tocca una linea per vederne il percorso" else "Percorso evidenziato sulla mappa",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.heightIn(max = 210.dp).padding(top = 4.dp)) {
                items(stop.lines, key = { "${it.key.routeId}|${it.key.direction}|${it.key.headsign}" }) { line ->
                    val active = line == previewLine
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { onLine(line) }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LineBadge(line.routeName, line.routeType)
                        Spacer(Modifier.width(10.dp))
                        Text("→ " + Format.prettyName(line.key.headsign), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (active && route != null && route.pattern == null) {
                            Text("percorso non disponibile", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Button(onClick = onPick, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Scegli le linee di questa fermata") }
        }
    }
}

/** Pannello del percorso: linea, capolinea e fermate in ordine (toccandole la mappa ci si sposta). */
@Composable
private fun RoutePanel(route: ShownRoute, highlight: String?, onStop: (Stop) -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LineBadge(route.routeName, route.routeType)
            Spacer(Modifier.width(10.dp))
            Text("→ " + Format.prettyName(route.headsign), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val pattern = route.pattern
        if (pattern == null) {
            Text("Percorso non disponibile: aggiorna gli orari dalla schermata principale.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            return@Panel
        }
        Text(
            "${pattern.stops.size} fermate · da ${Format.prettyName(pattern.stops.first().name)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        val color = lineColor(route.routeName, route.routeType)
        val state = rememberLazyListState()
        val index = pattern.stops.indexOfFirst { it.id == highlight }
        LaunchedEffect(index) { if (index > 2) state.scrollToItem(index - 2) }
        LazyColumn(state = state, modifier = Modifier.heightIn(max = 230.dp)) {
            itemsIndexed(pattern.stops) { i, stop ->
                val isHere = stop.id == highlight
                Row(
                    Modifier.fillMaxWidth().clickable { onStop(stop) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // "linea della metro": tratto verticale con un pallino per fermata
                    Box(Modifier.width(24.dp).height(30.dp)) {
                        Box(
                            Modifier.align(Alignment.Center).width(4.dp).fillMaxHeight()
                                .padding(top = if (i == 0) 15.dp else 0.dp, bottom = if (i == pattern.stops.lastIndex) 15.dp else 0.dp)
                                .background(color),
                        )
                        Box(
                            Modifier.align(Alignment.Center).size(if (isHere) 16.dp else 12.dp)
                                .background(Color.White, CircleShape)
                                .border(3.dp, if (isHere) MaterialTheme.colorScheme.primary else color, CircleShape),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Format.prettyName(stop.name),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isHere || i == 0 || i == pattern.stops.lastIndex) FontWeight.Bold else FontWeight.Normal,
                        color = if (isHere) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isHere) Text("questa fermata", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) { content() }
    }
}

/** Obbligatoria per i tile di OpenStreetMap. */
@Composable
private fun Attribution() {
    Text(
        "© OpenStreetMap contributors",
        fontSize = 10.sp,
        color = Color.DarkGray,
        modifier = Modifier.background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(topEnd = 6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
