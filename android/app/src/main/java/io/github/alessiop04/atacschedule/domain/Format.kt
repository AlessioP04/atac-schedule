package io.github.alessiop04.atacschedule.domain

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

object Format {
    val IT: Locale = Locale.ITALIAN

    /** 7 -> "00:07", 1439 -> "23:59" */
    fun time(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

    /** "lun", "mar", ... */
    fun weekday(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, IT).removeSuffix(".")

    /** "lun 23 nov" */
    fun day(date: LocalDate) = "${weekday(date)} ${date.dayOfMonth} ${month(date)}"

    fun month(date: LocalDate): String = date.month.getDisplayName(TextStyle.SHORT, IT).removeSuffix(".")

    /** "mer 18 – mar 24 nov", "lun 30 nov – ven 4 dic" */
    fun range(days: List<LocalDate>): String = when {
        days.isEmpty() -> ""
        days.size == 1 -> day(days[0])
        days.first().month == days.last().month ->
            "${weekday(days.first())} ${days.first().dayOfMonth} – ${day(days.last())}"
        else -> "${day(days.first())} – ${day(days.last())}"
    }

    /**
     * Etichetta di un orario straordinario: "solo lun" se avviene in pochi giorni, "no ven" se manca in pochi giorni.
     * In una vista feriale ogni giorno della settimana compare una volta sola, quindi basta il nome del giorno.
     */
    fun exceptionLabel(departureDays: List<LocalDate>, allDays: List<LocalDate>): String =
        if (departureDays.size * 2 <= allDays.size) departureDays.joinToString(" ", transform = ::weekday)
        else "no " + (allDays - departureDays.toSet()).joinToString(" ", transform = ::weekday)

    /** "tra 4 min", "tra 1 h 05", "ora" */
    fun wait(minutes: Long): String = when {
        minutes <= 0 -> "ora"
        minutes < 60 -> "tra $minutes min"
        else -> "tra %d h %02d".format(minutes / 60, minutes % 60)
    }

    private val KEEP_UPPER = setOf(
        "FS", "FL", "MA", "MB", "MC", "MB1", "RL", "ATAC", "GRA", "II", "III", "IV", "VI", "VII", "VIII", "IX",
        "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX", "XXI", "XXII", "XXIII", "XXIV", "S.",
    )
    private val LOWER = setOf("di", "de", "del", "della", "delle", "dei", "degli", "da", "dal", "dalla", "e", "a", "al", "alla", "in", "la", "le", "lo", "il", "per", "su", "sul")
    private val WORD = Regex("[\\p{L}\\p{N}.']+")

    /**
     * Nomi GTFS in maiuscolo -> forma leggibile: "CADUTI LIBERAZIONE/MARZABOTTO" -> "Caduti Liberazione/Marzabotto",
     * "EUR FERMI (MB)" -> "Eur Fermi (MB)", "P.ZA DI PORTA MAGGIORE" -> "P.za di Porta Maggiore".
     */
    fun prettyName(raw: String): String {
        if (raw.any { it.isLowerCase() }) return raw
        var first = true
        return WORD.replace(raw.trim()) { m ->
            val w = m.value
            val inParens = m.range.first > 0 && raw[m.range.first - 1] == '('
            val result = when {
                inParens && w.length <= 4 -> w                       // sigle tra parentesi: (MB), (FS)
                w in KEEP_UPPER -> w
                !first && w.lowercase(IT) in LOWER -> w.lowercase(IT)
                else -> capitalizeWord(w.lowercase(IT))
            }
            first = false
            result
        }
    }

    /** "l'arco" -> "L'Arco", "s.giovanni" -> "S.Giovanni", ma "p.za" -> "P.za" e "staz.ne" -> "Staz.ne" (abbreviazioni). */
    private fun capitalizeWord(w: String): String {
        val parts = w.split('.')
        return parts.mapIndexed { i, part ->
            val isAbbreviationTail = i > 0 && i == parts.lastIndex && part.length <= 3
            if (isAbbreviationTail) part else part.split('\'').joinToString("'") { it.replaceFirstChar { c -> c.titlecase(IT) } }
        }.joinToString(".")
    }

    /** Ordina le linee come su una palina: 8, 63, 705, 706, C2, n705 */
    val routeOrder: Comparator<String> = compareBy<String>(
        { if (it.startsWith("n") && it.length > 1) 1 else 0 },
        { it.takeWhile(Char::isDigit).ifEmpty { null }?.toIntOrNull() ?: Int.MAX_VALUE },
        { it },
    )
}
