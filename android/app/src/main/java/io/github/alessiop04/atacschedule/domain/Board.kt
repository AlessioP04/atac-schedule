package io.github.alessiop04.atacschedule.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Orari di una linea a una fermata, data per data (minuti dalla mezzanotte, ordinati).
 * Le date dentro la validità ma assenti dalla mappa sono giorni senza corse.
 */
class LineSchedule(
    val byDate: Map<LocalDate, IntArray>,
    val validFrom: LocalDate,
    val validTo: LocalDate,
) {
    fun isCovered(date: LocalDate) = !date.isBefore(validFrom) && !date.isAfter(validTo)

    fun timesOn(date: LocalDate): IntArray = byDate[date] ?: EMPTY

    /** Prossimi passaggi da `now` in poi, anche nei giorni successivi (max `count`). */
    fun upcoming(now: LocalDateTime, count: Int, maxDays: Int = 3): List<LocalDateTime> {
        val result = ArrayList<LocalDateTime>(count)
        var day = now.toLocalDate()
        val nowMinute = now.hour * 60 + now.minute
        repeat(maxDays) { offset ->
            if (!isCovered(day)) return result
            for (m in timesOn(day)) {
                if (offset == 0 && m < nowMinute) continue
                result += day.atStartOfDay().plusMinutes(m.toLong())
                if (result.size == count) return result
            }
            day = day.plusDays(1)
        }
        return result
    }

    private companion object {
        val EMPTY = IntArray(0)
    }
}

/** Quali giorni mostrare: i feriali (5 giorni consecutivi da lunedì a venerdì), un sabato, una domenica o un giorno qualsiasi. */
sealed interface Period {
    val days: List<LocalDate>

    /** Cinque feriali consecutivi a partire da `start` (es. mer, gio, ven, lun, mar). */
    data class Weekdays(val start: LocalDate) : Period {
        override val days: List<LocalDate> = generateSequence(start) { it.plusDays(1) }
            .filter { it.dayOfWeek < DayOfWeek.SATURDAY }
            .take(5)
            .toList()
    }

    data class Single(val date: LocalDate) : Period {
        override val days = listOf(date)
    }

    companion object {
        fun weekdaysFrom(today: LocalDate): Weekdays =
            Weekdays(if (today.dayOfWeek >= DayOfWeek.SATURDAY) today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) else today)

        fun next(today: LocalDate, day: DayOfWeek): Single =
            Single(today.with(TemporalAdjusters.nextOrSame(day)))
    }
}

fun Period.shiftWeeks(weeks: Long): Period = when (this) {
    is Period.Weekdays -> Period.Weekdays(start.plusWeeks(weeks).let {
        // un periodo feriale parte sempre da un feriale
        if (it.dayOfWeek >= DayOfWeek.SATURDAY) it.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) else it
    })
    is Period.Single -> Period.Single(date.plusWeeks(weeks))
}

/**
 * Un passaggio nella tabella. `days` sono i giorni (della vista) in cui avviene:
 * se coincidono con tutti i giorni della vista l'orario è "normale", altrimenti è straordinario.
 */
data class Departure(val minute: Int, val days: List<LocalDate>)

/**
 * Tabella pronta da mostrare.
 * @param days giorni effettivamente rappresentati in [departures]
 * @param specialDays giorni con un orario troppo diverso dagli altri (es. festivi infrasettimanali): si consultano a parte
 * @param uncoveredDays giorni fuori dalla validità dei dati
 */
data class Board(
    val days: List<LocalDate>,
    val departures: List<Departure>,
    val specialDays: List<LocalDate>,
    val uncoveredDays: List<LocalDate>,
) {
    fun isRegular(d: Departure) = d.days.size == days.size

    val hasIrregular: Boolean get() = departures.any { !isRegular(it) }
}

object BoardBuilder {
    /** Un giorno è "speciale" se differisce dalla maggioranza per più di così tanti passaggi (minimo assoluto e quota). */
    private const val SPECIAL_MIN_DIFF = 4
    private const val SPECIAL_SHARE = 0.25

    fun build(schedule: LineSchedule, period: Period): Board {
        val (covered, uncovered) = period.days.partition(schedule::isCovered)
        val sets = covered.associateWith { schedule.timesOn(it).toSortedSet() }

        val special = if (covered.size >= 3) findSpecialDays(sets) else emptyList()
        val days = covered - special.toSet()

        val departures = days.flatMap { sets.getValue(it) }.toSortedSet().map { minute ->
            Departure(minute, days.filter { minute in sets.getValue(it) })
        }
        return Board(days, departures, special, uncovered)
    }

    /** Giorni molto diversi dall'orario "di maggioranza" (presente in più di metà dei giorni). */
    private fun findSpecialDays(sets: Map<LocalDate, Set<Int>>): List<LocalDate> {
        val counts = HashMap<Int, Int>()
        sets.values.forEach { s -> s.forEach { counts.merge(it, 1, Int::plus) } }
        val majority = counts.filterValues { it * 2 > sets.size }.keys
        val threshold = maxOf(SPECIAL_MIN_DIFF, (majority.size * SPECIAL_SHARE).toInt())
        val special = sets.filter { (_, s) -> (s - majority).size + (majority - s).size > threshold }.keys.toList()
        // se quasi tutti i giorni sono "speciali" non esiste un orario tipico: meglio mostrarli insieme
        return if (special.size * 2 > sets.size) emptyList() else special
    }
}
