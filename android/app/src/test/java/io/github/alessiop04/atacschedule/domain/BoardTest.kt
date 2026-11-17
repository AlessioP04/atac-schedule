package io.github.alessiop04.atacschedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class BoardTest {
    private val mon = LocalDate.of(2026, 11, 23)
    private val tue = mon.plusDays(1)
    private val wed = mon.plusDays(2)
    private val thu = mon.plusDays(3)
    private val fri = mon.plusDays(4)
    private val sat = mon.plusDays(5)

    private fun hm(h: Int, m: Int) = h * 60 + m
    private fun schedule(vararg days: Pair<LocalDate, List<Int>>, from: LocalDate = mon, to: LocalDate = mon.plusDays(30)) =
        LineSchedule(days.associate { (d, t) -> d to t.sorted().toIntArray() }, from, to)

    private val base = listOf(hm(6, 0), hm(6, 30), hm(7, 0), hm(7, 20), hm(7, 40), hm(8, 0), hm(9, 0), hm(10, 0), hm(12, 0), hm(18, 0))

    @Test
    fun `feriali identici - nessun orario straordinario`() {
        val s = schedule(mon to base, tue to base, wed to base, thu to base, fri to base)
        val b = BoardBuilder.build(s, Period.Weekdays(mon))
        assertEquals(listOf(mon, tue, wed, thu, fri), b.days)
        assertEquals(base, b.departures.map { it.minute })
        assertFalse(b.hasIrregular)
        assertTrue(b.specialDays.isEmpty())
    }

    @Test
    fun `corsa in piu il lunedi e corsa in meno il venerdi`() {
        val s = schedule(
            mon to base + hm(0, 15), tue to base, wed to base, thu to base, fri to base - hm(18, 0),
        )
        val b = BoardBuilder.build(s, Period.Weekdays(mon))
        val extra = b.departures.first { it.minute == hm(0, 15) }
        assertEquals(listOf(mon), extra.days)
        assertEquals("lun", Format.exceptionLabel(extra.days, b.days))
        val missing = b.departures.first { it.minute == hm(18, 0) }
        assertEquals("no ven", Format.exceptionLabel(missing.days, b.days))
        assertTrue(b.isRegular(b.departures.first { it.minute == hm(6, 0) }))
    }

    @Test
    fun `festivo infrasettimanale diventa un giorno speciale`() {
        val holiday = listOf(hm(8, 0), hm(11, 0), hm(15, 0))
        val s = schedule(mon to base, tue to holiday, wed to base, thu to base, fri to base)
        val b = BoardBuilder.build(s, Period.Weekdays(mon))
        assertEquals(listOf(tue), b.specialDays)
        assertEquals(listOf(mon, wed, thu, fri), b.days)
        assertFalse(b.hasIrregular)
    }

    @Test
    fun `giorno senza corse diventa speciale`() {
        val s = schedule(mon to base, wed to base, thu to base, fri to base) // martedì: nessuna corsa
        val b = BoardBuilder.build(s, Period.Weekdays(mon))
        assertEquals(listOf(tue), b.specialDays)
    }

    @Test
    fun `giorni fuori validita`() {
        val s = schedule(mon to base, tue to base, wed to base, to = wed)
        val b = BoardBuilder.build(s, Period.Weekdays(mon))
        assertEquals(listOf(thu, fri), b.uncoveredDays)
        assertEquals(listOf(mon, tue, wed), b.days)
    }

    @Test
    fun `finestra feriale da mercoledi salta il weekend`() {
        assertEquals(listOf(wed, thu, fri, mon.plusWeeks(1), tue.plusWeeks(1)), Period.Weekdays(wed).days)
        assertEquals(Period.Weekdays(mon.plusWeeks(1)), Period.weekdaysFrom(sat))
        assertEquals(Period.Single(sat), Period.next(wed, java.time.DayOfWeek.SATURDAY))
    }

    @Test
    fun `prossimi passaggi anche il giorno dopo`() {
        val s = schedule(mon to listOf(hm(22, 0), hm(23, 30)), tue to listOf(hm(5, 0), hm(6, 0)))
        val next = s.upcoming(LocalDateTime.of(mon, java.time.LocalTime.of(23, 0)), 3)
        assertEquals(
            listOf(mon.atTime(23, 30), tue.atTime(5, 0), tue.atTime(6, 0)),
            next,
        )
    }
}
