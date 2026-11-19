package io.github.alessiop04.atacschedule.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PolylineTest {
    @Test
    fun decodeGoogleExample() {
        // esempio della documentazione di Google
        val points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertArrayEquals(doubleArrayOf(38.5, -120.2, 40.7, -120.95, 43.252, -126.453), points, 1e-9)
    }
}
