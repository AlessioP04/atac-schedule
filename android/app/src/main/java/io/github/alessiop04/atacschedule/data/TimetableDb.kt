package io.github.alessiop04.atacschedule.data

import android.database.sqlite.SQLiteDatabase
import io.github.alessiop04.atacschedule.domain.Format
import io.github.alessiop04.atacschedule.domain.Geo
import io.github.alessiop04.atacschedule.domain.LineSchedule
import java.io.Closeable
import java.io.File
import java.time.LocalDate

/** Identifica una linea a una fermata in modo stabile tra un database e il successivo (vedi pipeline/schema.sql). */
data class LineKey(val stopId: String, val routeId: String, val direction: Int, val headsign: String)

data class Stop(val id: String, val name: String, val lat: Double, val lon: Double)

data class LineAtStop(val key: LineKey, val routeName: String, val routeType: Int)

/** @param distance metri dalla posizione dell'utente (solo nella ricerca "vicino a me") */
data class StopResult(val stop: Stop, val lines: List<LineAtStop>, val distance: Double? = null)

data class DbMeta(
    val schemaVersion: Int,
    val generatedAt: String,
    val feedSha256: String,
    val validFrom: LocalDate,
    val validTo: LocalDate,
)

/** Accesso in sola lettura a timetable.db. Il contratto è pipeline/schema.sql. */
class TimetableDb private constructor(private val db: SQLiteDatabase) : Closeable {

    val meta: DbMeta = readMeta()

    fun stop(id: String): Stop? =
        db.rawQuery("SELECT id, name, lat, lon FROM stops WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) Stop(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3)) else null
        }

    /** Fermate per codice esatto o per nome (tutte le parole, in qualsiasi ordine). */
    fun searchStops(query: String, limit: Int = 40): List<StopResult> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val nameMatch = words.joinToString(" AND ") { "name LIKE ? ESCAPE '\\'" }
        val args = buildList {
            add(query.trim())
            words.forEach { add("%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%") }
            add(query.trim())
        }
        val stops = db.rawQuery(
            """SELECT id, name, lat, lon FROM stops
               WHERE id = ? OR ($nameMatch)
               ORDER BY id = ? DESC, name, id LIMIT $limit""",
            args.toTypedArray(),
        ).use { c ->
            buildList { while (c.moveToNext()) add(Stop(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3))) }
        }
        val lines = linesAt(stops.map { it.id })
        return stops.map { StopResult(it, lines[it.id].orEmpty()) }
    }

    /** Fermate entro `radius` metri (con almeno una linea), dalla più vicina; se sono poche allarga la ricerca. */
    fun nearbyStops(lat: Double, lon: Double, radius: Double = 500.0, limit: Int = 25): List<StopResult> {
        var r = radius
        var found: List<Pair<Stop, Double>>
        do {
            val (minLat, maxLat, minLon, maxLon) = Geo.box(lat, lon, r).toList()
            found = db.rawQuery(
                "SELECT id, name, lat, lon FROM stops WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
                arrayOf(minLat.toString(), maxLat.toString(), minLon.toString(), maxLon.toString()),
            ).use { c ->
                buildList { while (c.moveToNext()) add(Stop(c.getString(0), c.getString(1), c.getDouble(2), c.getDouble(3))) }
            }.map { it to Geo.distance(lat, lon, it.lat, it.lon) }.filter { it.second <= r }
            r *= 2
        } while (found.size < 5 && r <= radius * 8)
        val nearest = found.sortedBy { it.second }.take(limit)
        val lines = linesAt(nearest.map { it.first.id })
        return nearest.map { (stop, d) -> StopResult(stop, lines[stop.id].orEmpty(), d) }
    }

    fun linesAt(stopId: String): List<LineAtStop> = linesAt(listOf(stopId))[stopId].orEmpty()

    private fun linesAt(stopIds: List<String>): Map<String, List<LineAtStop>> {
        if (stopIds.isEmpty()) return emptyMap()
        val placeholders = stopIds.joinToString(",") { "?" }
        return db.rawQuery(
            """SELECT l.stop_id, l.route_id, l.direction, l.headsign, r.name, r.type
               FROM lines l JOIN routes r ON r.id = l.route_id
               WHERE l.stop_id IN ($placeholders)""",
            stopIds.toTypedArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    LineAtStop(LineKey(c.getString(0), c.getString(1), c.getInt(2), c.getString(3)), c.getString(4), c.getInt(5))
                )
            }
        }.sortedWith(compareBy(Format.routeOrder) { it: LineAtStop -> it.routeName }.thenBy { it.key.direction }.thenBy { it.key.headsign })
            .groupBy { it.key.stopId }
    }

    /**
     * Orari di una linea a una fermata in ogni data coperta dal database.
     * Se il capolinea non esiste più (rinominato in un feed successivo) si usano tutti i capolinea della stessa direzione.
     */
    fun schedule(key: LineKey): LineSchedule {
        var lineIds = lineIds("stop_id = ? AND route_id = ? AND direction = ? AND headsign = ?",
            arrayOf(key.stopId, key.routeId, key.direction.toString(), key.headsign))
        if (lineIds.isEmpty()) {
            lineIds = lineIds("stop_id = ? AND route_id = ? AND direction = ?",
                arrayOf(key.stopId, key.routeId, key.direction.toString()))
        }
        val byDate = HashMap<LocalDate, IntArray>()
        if (lineIds.isNotEmpty()) {
            db.rawQuery(
                """SELECT d.date, t.times
                   FROM schedules s
                   JOIN day_sets d   ON d.id = s.day_set_id
                   JOIN timetables t ON t.id = s.timetable_id
                   WHERE s.line_id IN (${lineIds.joinToString(",")})""",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    val date = parseDate(c.getInt(0))
                    val times = decodeTimes(c.getBlob(1))
                    byDate.merge(date, times) { a, b -> (a + b).also { it.sort() } }
                }
            }
        }
        return LineSchedule(byDate, meta.validFrom, meta.validTo)
    }

    private fun lineIds(where: String, args: Array<String>): List<Long> =
        db.rawQuery("SELECT id FROM lines WHERE $where", args).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }

    private fun readMeta(): DbMeta {
        val values = db.rawQuery("SELECT key, value FROM meta", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
        return DbMeta(
            schemaVersion = db.version,
            generatedAt = values["generated_at"].orEmpty(),
            feedSha256 = values["feed_sha256"].orEmpty(),
            validFrom = parseDate(values.getValue("valid_from").toInt()),
            validTo = parseDate(values.getValue("valid_to").toInt()),
        )
    }

    override fun close() = db.close()

    companion object {
        /** Versione di pipeline/schema.sql che questa app sa leggere (PRAGMA user_version). */
        const val SCHEMA_VERSION = 1

        /** Apre e verifica un database: lancia un'eccezione se è corrotto o di uno schema diverso. */
        fun open(file: File): TimetableDb {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            try {
                check(db.version == SCHEMA_VERSION) { "schema v${db.version}, atteso v$SCHEMA_VERSION" }
                return TimetableDb(db)
            } catch (e: Exception) {
                db.close()
                throw e
            }
        }

        fun parseDate(yyyymmdd: Int): LocalDate = LocalDate.of(yyyymmdd / 10000, yyyymmdd / 100 % 100, yyyymmdd % 100)

        /** uint16 little-endian -> minuti dalla mezzanotte. */
        fun decodeTimes(blob: ByteArray): IntArray =
            IntArray(blob.size / 2) { i -> (blob[2 * i].toInt() and 0xFF) or ((blob[2 * i + 1].toInt() and 0xFF) shl 8) }
    }
}
