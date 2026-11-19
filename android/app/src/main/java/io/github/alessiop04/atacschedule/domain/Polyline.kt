package io.github.alessiop04.atacschedule.domain

/** Decodifica l'Encoded Polyline di Google (precisione 1e-5) scritta da pipeline/shapes.py. */
object Polyline {
    /** Coppie (lat, lon) consecutive: [lat0, lon0, lat1, lon1, ...] */
    fun decode(text: String): DoubleArray {
        val out = ArrayList<Double>(text.length / 2)
        var i = 0
        var lat = 0
        var lon = 0
        while (i < text.length) {
            for (k in 0..1) {
                var shift = 0
                var result = 0
                while (true) {
                    val b = text[i++].code - 63
                    result = result or ((b and 0x1F) shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val delta = if (result and 1 != 0) (result shr 1).inv() else result shr 1
                if (k == 0) lat += delta else lon += delta
            }
            out += lat / 1e5
            out += lon / 1e5
        }
        return out.toDoubleArray()
    }
}
