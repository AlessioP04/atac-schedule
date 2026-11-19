package io.github.alessiop04.atacschedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test
    fun distance() {
        // Colosseo -> Piazza Venezia: circa 1,1 km
        val d = Geo.distance(41.8902, 12.4922, 41.8960, 12.4823)
        assertTrue(d in 900.0..1200.0)
        assertEquals("1,0 km", Geo.format(1000.0))
        assertEquals("80 m", Geo.format(78.0))
    }

    @Test
    fun boxContainsCircle() {
        val (minLat, maxLat, minLon, maxLon) = Geo.box(41.9, 12.5, 500.0).toList()
        assertTrue(Geo.distance(41.9, 12.5, maxLat, 12.5) in 499.0..501.0)
        assertTrue(Geo.distance(41.9, 12.5, 41.9, minLon) in 499.0..501.0)
        assertTrue(minLat < 41.9 && maxLon > 12.5)
    }

    @Test
    fun versions() {
        assertTrue(Versions.isNewer("v0.2.0", "0.1.0"))
        assertTrue(Versions.isNewer("0.10.0", "0.9.3"))
        assertTrue(Versions.isNewer("1.0.0", "0.9.9"))
        assertFalse(Versions.isNewer("v0.1.0", "0.1.0"))
        assertFalse(Versions.isNewer("0.1.0", "0.2.0"))
    }
}
