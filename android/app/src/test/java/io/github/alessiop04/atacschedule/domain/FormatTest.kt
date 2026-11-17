package io.github.alessiop04.atacschedule.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FormatTest {
    @Test
    fun prettyName() {
        assertEquals("Caduti Liberazione/Marzabotto", Format.prettyName("CADUTI LIBERAZIONE/MARZABOTTO"))
        assertEquals("Eur Fermi (MB)", Format.prettyName("EUR FERMI (MB)"))
        assertEquals("P.za di Porta Maggiore", Format.prettyName("P.ZA DI PORTA MAGGIORE"))
        assertEquals("Termini (MA-MB-FS)", Format.prettyName("TERMINI (MA-MB-FS)"))
        assertEquals("Ostiense/Staz.ne Acilia (RL)", Format.prettyName("OSTIENSE/STAZ.NE ACILIA (RL)"))
        assertEquals("Già scritto bene", Format.prettyName("Già scritto bene"))
    }

    @Test
    fun times() {
        assertEquals("00:07", Format.time(7))
        assertEquals("23:59", Format.time(1439))
        assertEquals("tra 4 min", Format.wait(4))
        assertEquals("tra 1 h 05", Format.wait(65))
        assertEquals("ora", Format.wait(0))
    }

    @Test
    fun days() {
        val wed = LocalDate.of(2026, 11, 18)
        assertEquals("mer", Format.weekday(wed))
        assertEquals("mer 18 nov", Format.day(wed))
        assertEquals("mer 18 – mar 24 nov", Format.range(Period.Weekdays(wed).days))
        assertEquals("lun 30 nov – ven 4 dic", Format.range(Period.Weekdays(LocalDate.of(2026, 11, 30)).days))
    }

    @Test
    fun routeOrder() {
        val sorted = listOf("n705", "C2", "706", "8", "63", "705", "04B").sortedWith(Format.routeOrder)
        assertEquals(listOf("04B", "8", "63", "705", "706", "C2", "n705"), sorted)
    }
}
