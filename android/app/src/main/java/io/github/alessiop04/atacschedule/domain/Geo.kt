package io.github.alessiop04.atacschedule.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

object Geo {
    private const val METERS_PER_DEGREE = 111_320.0

    /** Distanza in metri; l'approssimazione equirettangolare è più che precisa su scala cittadina. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val x = (lon2 - lon1) * cos((lat1 + lat2) / 2 * PI / 180)
        val y = lat2 - lat1
        return sqrt(x * x + y * y) * METERS_PER_DEGREE
    }

    /** Riquadro (minLat, maxLat, minLon, maxLon) che contiene il cerchio di raggio `meters`. */
    fun box(lat: Double, lon: Double, meters: Double): DoubleArray {
        val dLat = meters / METERS_PER_DEGREE
        val dLon = dLat / cos(lat * PI / 180)
        return doubleArrayOf(lat - dLat, lat + dLat, lon - dLon, lon + dLon)
    }

    /** "80 m", "1,2 km" */
    fun format(meters: Double): String =
        if (meters < 1000) "${(meters / 10).roundToInt() * 10} m"
        else "%.1f km".format(Format.IT, meters / 1000)
}

/** Confronto tra versioni "X.Y.Z" (anche con "v" davanti). */
object Versions {
    fun parse(v: String): List<Int> = v.trim().removePrefix("v").split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
