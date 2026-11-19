package io.github.alessiop04.atacschedule.ui.map

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import io.github.alessiop04.atacschedule.BuildConfig
import io.github.alessiop04.atacschedule.data.RoutePattern
import io.github.alessiop04.atacschedule.data.Stop
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.simplefastpoint.LabelledGeoPoint
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme
import java.io.File

/** Sotto questo zoom le ~8.000 fermate sarebbero solo un tappeto di puntini: si nascondono. */
const val STOPS_MIN_ZOOM = 15.0

val ROME_CENTER = GeoPoint(41.8960, 12.4823)

/** La mappa osmdroid e i suoi livelli (fermate, percorso, selezione, posizione), gestiti da Compose. */
class MapLayers(context: Context, dark: Boolean) {
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    val view: MapView

    private var stopsOverlay: SimpleFastPointOverlay? = null
    private var routeLine: Polyline? = null
    private var routeStops: SimpleFastPointOverlay? = null
    private var selected: Marker? = null
    private var myLocation: MyLocationNewOverlay? = null

    /** Chiamata a ogni cambio di zoom, con il nuovo livello. */
    var onZoom: (Double) -> Unit = {}

    init {
        Configuration.getInstance().apply {
            userAgentValue = BuildConfig.APPLICATION_ID // richiesto dalle regole d'uso dei tile OpenStreetMap
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        view = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 10.0
            maxZoomLevel = 19.0
            isTilesScaledToDpi = true
            isVerticalMapRepetitionEnabled = false
            if (dark) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?) = false
                override fun onZoom(event: ZoomEvent?): Boolean {
                    val z = zoomLevelDouble
                    stopsOverlay?.isEnabled = z >= STOPS_MIN_ZOOM
                    onZoom(z)
                    return false
                }
            })
        }
    }

    /** Tutte le fermate come puntini toccabili (visibili da [STOPS_MIN_ZOOM] in su). */
    fun setStops(stops: List<Stop>, onClick: (Stop) -> Unit) {
        stopsOverlay?.let { view.overlays.remove(it) }
        val overlay = pointsOverlay(stops, fill = Color.rgb(0x45, 0x5A, 0x64), radius = 6f, onClick)
        overlay.isEnabled = view.zoomLevelDouble >= STOPS_MIN_ZOOM
        view.overlays.add(0, overlay) // sotto percorso e selezione
        stopsOverlay = overlay
        view.invalidate()
    }

    /** Percorso di una linea: linea del suo colore e fermate come pallini bianchi. Null = nessun percorso. */
    fun setRoute(pattern: RoutePattern?, color: Int, onStopClick: (Stop) -> Unit) {
        routeLine?.let { view.overlays.remove(it) }
        routeStops?.let { view.overlays.remove(it) }
        routeLine = null
        routeStops = null
        if (pattern != null) {
            val line = Polyline(view).apply {
                setPoints(geoPoints(pattern.shape))
                outlinePaint.apply {
                    this.color = color
                    strokeWidth = dp(6f)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                }
                setOnClickListener { _, _, _ -> false } // nessun fumetto: il tocco passa alle fermate
            }
            val stops = pointsOverlay(pattern.stops, fill = Color.WHITE, radius = 4.5f, onStopClick, stroke = color)
            val index = view.overlays.indexOf(selected).takeIf { it >= 0 } ?: view.overlays.size
            view.overlays.add(index, line)
            view.overlays.add(index + 1, stops)
            routeLine = line
            routeStops = stops
        }
        view.invalidate()
    }

    /** Evidenzia la fermata scelta (sopra tutto il resto). */
    fun setSelected(stop: Stop?, color: Int) {
        selected?.let { view.overlays.remove(it) }
        selected = stop?.let {
            Marker(view).apply {
                position = GeoPoint(it.lat, it.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(3f).toInt(), Color.WHITE)
                    setSize(dp(22f).toInt(), dp(22f).toInt())
                }
                infoWindow = null
                setOnMarkerClickListener { _, _ -> true }
            }.also { m -> view.overlays.add(m) }
        }
        view.invalidate()
    }

    private var place: Marker? = null

    /** Segnaposto del luogo cercato (via, piazza...). */
    fun setPlace(lat: Double?, lon: Double?) {
        place?.let { view.overlays.remove(it) }
        place = if (lat == null || lon == null) null else Marker(view).apply {
            position = GeoPoint(lat, lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(0x1A, 0x73, 0xE8))
                setStroke(dp(3f).toInt(), Color.WHITE)
                setSize(dp(18f).toInt(), dp(18f).toInt())
            }
            infoWindow = null
            setOnMarkerClickListener { _, _ -> true }
        }.also { view.overlays.add(it) }
        view.invalidate()
    }

    /** Il pallino blu della posizione (solo se l'app ha il permesso). */
    fun showMyLocation() {
        if (myLocation != null) return
        myLocation = MyLocationNewOverlay(GpsMyLocationProvider(view.context), view).apply {
            enableMyLocation()
            view.overlays.add(this)
        }
    }

    fun moveTo(lat: Double, lon: Double, zoom: Double?, animate: Boolean = true) {
        val target = GeoPoint(lat, lon)
        if (animate) view.controller.animateTo(target, zoom ?: view.zoomLevelDouble, 400L)
        else {
            zoom?.let { view.controller.setZoom(it) }
            view.controller.setCenter(target)
        }
    }

    /** Inquadra un percorso lasciando spazio ai pannelli sopra e sotto la mappa. */
    fun fit(shape: DoubleArray, paddingPx: Int) {
        if (shape.size < 4) return
        val b = BoundingBox.fromGeoPointsSafe(geoPoints(shape))
        // più spazio in alto: lì ci sono la barra di ricerca e il pulsante indietro
        val box = BoundingBox(b.latNorth + b.latitudeSpan * 0.22, b.lonEast, b.latSouth, b.lonWest)
        val apply = { view.zoomToBoundingBox(box, true, paddingPx) }
        if (view.width > 0) apply() else view.addOnFirstLayoutListener { _, _, _, _, _ -> apply() }
    }

    fun onResume() {
        view.onResume()
        myLocation?.enableMyLocation()
    }

    fun onPause() {
        myLocation?.disableMyLocation()
        view.onPause()
    }

    fun onDestroy() = view.onDetach()

    private fun geoPoints(shape: DoubleArray) = List(shape.size / 2) { GeoPoint(shape[2 * it], shape[2 * it + 1]) }

    private fun pointsOverlay(stops: List<Stop>, fill: Int, radius: Float, onClick: (Stop) -> Unit, stroke: Int? = null): SimpleFastPointOverlay {
        val points = stops.map { LabelledGeoPoint(it.lat, it.lon, it.id) }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill; style = Paint.Style.FILL }
        val options = SimpleFastPointOverlayOptions.getDefaultStyle()
            .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MAXIMUM_OPTIMIZATION)
            .setSymbol(SimpleFastPointOverlayOptions.Shape.CIRCLE)
            .setRadius(dp(radius))
            .setCellSize(dp(8f).toInt())
            .setIsClickable(true)
            .setPointStyle(fillPaint)
            .setSelectedPointStyle(Paint().apply { color = Color.TRANSPARENT })
        return object : SimpleFastPointOverlay(SimplePointTheme(points, false), options) {
            // bordo colorato attorno ai pallini bianchi del percorso
            private val ring = stroke?.let { c ->
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; style = Paint.Style.STROKE; strokeWidth = dp(2.5f) }
            }

            override fun drawPointAt(canvas: android.graphics.Canvas, x: Float, y: Float, showLabel: Boolean, label: String?, pointStyle: Paint, textStyle: Paint?, mapView: MapView?) {
                super.drawPointAt(canvas, x, y, showLabel, label, pointStyle, textStyle, mapView)
                ring?.let { canvas.drawCircle(x, y, dp(radius), it) }
            }
        }.apply {
            setOnClickListener { _, index -> onClick(stops[index]) }
        }
    }
}
