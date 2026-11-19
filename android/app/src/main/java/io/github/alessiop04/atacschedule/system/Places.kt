package io.github.alessiop04.atacschedule.system

import android.net.Uri
import android.util.Log
import io.github.alessiop04.atacschedule.domain.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** Un luogo o un indirizzo trovato sulla mappa (non una fermata). */
data class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

/**
 * Ricerca di luoghi e indirizzi con Nominatim (OpenStreetMap), limitata all'area di Roma.
 * Regole d'uso di Nominatim: User-Agent riconoscibile, al massimo una richiesta al secondo,
 * niente ricerca a ogni lettera: per questo si chiama solo quando l'utente conferma la ricerca.
 */
object Places {
    // Riquadro dell'area servita (lon/lat): Roma e comuni serviti da ATAC e Roma TPL
    private const val VIEWBOX = "12.20,42.10,12.86,41.62"

    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        val url = Uri.parse("https://nominatim.openstreetmap.org/search").buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("format", "jsonv2")
            .appendQueryParameter("limit", "6")
            .appendQueryParameter("viewbox", VIEWBOX)
            .appendQueryParameter("bounded", "1")
            .appendQueryParameter("accept-language", "it")
            .build().toString()
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", "atac-schedule-android (github.com/AlessioP04/atac-schedule)")
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext emptyList()
                val array = JSONArray(conn.inputStream.reader().readText())
                val places = List(array.length()) { i ->
                    val o = array.getJSONObject(i)
                    val full = o.getString("display_name").split(", ")
                    Place(
                        name = o.optString("name").ifBlank { full.first() },
                        // "Via X, Quartiere, Roma, ..." -> si tengono solo i primi elementi utili
                        detail = full.drop(1).filterNot { it.startsWith("Roma Capitale") || it == "Lazio" || it == "Italia" || it.all(Char::isDigit) }
                            .take(3).joinToString(", "),
                        lat = o.getString("lat").toDouble(),
                        lon = o.getString("lon").toDouble(),
                    )
                }
                // OpenStreetMap divide spesso una piazza o una via in più elementi: si tiene il primo
                places.fold(emptyList<Place>()) { kept, p ->
                    if (kept.any { it.name == p.name && Geo.distance(it.lat, it.lon, p.lat, p.lon) < 200 }) kept else kept + p
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w("Places", "ricerca luoghi fallita", e)
            emptyList()
        }
    }
}
