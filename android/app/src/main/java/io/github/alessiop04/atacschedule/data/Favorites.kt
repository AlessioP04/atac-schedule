package io.github.alessiop04.atacschedule.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import io.github.alessiop04.atacschedule.domain.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Una linea salvata. `from`/`to` sono i nomi scelti dall'utente ("Spinaceto" → "Eur"):
 * se vuoti si usano il nome della fermata e il capolinea.
 */
data class Favorite(
    val id: Long,
    val key: LineKey,
    val routeName: String,
    val routeType: Int,
    val stopName: String,
    val from: String = "",
    val to: String = "",
) {
    val fromLabel: String get() = from.ifBlank { Format.prettyName(stopName) }
    val toLabel: String get() = to.ifBlank { Format.prettyName(key.headsign) }
}

/** Preferiti salvati in un piccolo file JSON, scritto in modo atomico. */
class FavoritesStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "favorites.json"))
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Favorite>> = _items.asStateFlow()

    fun get(id: Long): Favorite? = _items.value.firstOrNull { it.id == id }

    fun add(new: List<Favorite>) = edit { list ->
        var next = (list.maxOfOrNull { it.id } ?: 0) + 1
        list + new.filter { n -> list.none { it.key == n.key } }.map { it.copy(id = next++) }
    }

    fun rename(id: Long, from: String, to: String) = edit { list ->
        list.map { if (it.id == id) it.copy(from = from.trim(), to = to.trim()) else it }
    }

    fun remove(id: Long) = edit { list -> list.filterNot { it.id == id } }

    /** Annulla una rimozione, rimettendo il preferito al suo posto. */
    fun restore(favorite: Favorite, index: Int) = edit { list ->
        if (list.any { it.id == favorite.id || it.key == favorite.key }) list
        else list.toMutableList().apply { add(index.coerceIn(0, size), favorite) }
    }

    fun move(id: Long, delta: Int) = edit { list ->
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) list
        else list.toMutableList().apply { add(j, removeAt(i)) }
    }

    @Synchronized
    private fun edit(transform: (List<Favorite>) -> List<Favorite>) {
        _items.update(transform)
        save(_items.value)
    }

    private fun load(): List<Favorite> = try {
        val array = JSONArray(String(file.readFully(), Charsets.UTF_8))
        List(array.length()) { i -> array.getJSONObject(i).toFavorite() }
    } catch (_: java.io.FileNotFoundException) {
        emptyList()
    } catch (e: Exception) {
        Log.e("FavoritesStore", "preferiti illeggibili", e)
        emptyList()
    }

    private fun save(list: List<Favorite>) {
        val json = JSONArray(list.map { it.toJson() }).toString(1)
        val out = file.startWrite()
        try {
            out.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.e("FavoritesStore", "salvataggio fallito", e)
        }
    }

    private fun Favorite.toJson() = JSONObject()
        .put("id", id)
        .put("stop_id", key.stopId)
        .put("route_id", key.routeId)
        .put("direction", key.direction)
        .put("headsign", key.headsign)
        .put("route_name", routeName)
        .put("route_type", routeType)
        .put("stop_name", stopName)
        .put("from", from)
        .put("to", to)

    private fun JSONObject.toFavorite() = Favorite(
        id = getLong("id"),
        key = LineKey(getString("stop_id"), getString("route_id"), getInt("direction"), getString("headsign")),
        routeName = getString("route_name"),
        routeType = optInt("route_type", 3),
        stopName = optString("stop_name"),
        from = optString("from"),
        to = optString("to"),
    )
}
