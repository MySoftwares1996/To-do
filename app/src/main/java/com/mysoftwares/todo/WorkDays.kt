package com.mysoftwares.todo

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Which days are weekly offs. Used by the "Previous / Next working day" option.
 * Stored in device-protected storage so alarms can be computed before first unlock.
 */
object WorkDays {
    enum class OffDays(val label: String) {
        SAT_SUN("Saturday & Sunday"),
        SUN("Sunday only"),
        SUN_2ND_4TH_SAT("Sunday + 2nd & 4th Saturday"),
    }

    @Volatile var offDays: OffDays = OffDays.SAT_SUN
        private set

    /** Public / office holidays added by the user: date → name. */
    @Volatile var holidays: Map<LocalDate, String> = emptyMap()
        private set

    private fun prefs(c: Context) =
        c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("workdays", Context.MODE_PRIVATE)

    fun load(c: Context) {
        offDays = runCatching { OffDays.valueOf(prefs(c).getString("off", null) ?: "") }.getOrDefault(OffDays.SAT_SUN)
        holidays = prefs(c).getStringSet("holidays", emptySet())!!.mapNotNull { line ->
            val date = runCatching { LocalDate.parse(line.substringBefore("|")) }.getOrNull() ?: return@mapNotNull null
            date to line.substringAfter("|", "")
        }.toMap()
    }

    fun setHolidays(c: Context, list: Map<LocalDate, String>) {
        prefs(c).edit().putStringSet("holidays", list.map { (d, n) -> "$d|$n" }.toSet()).apply()
        holidays = list
    }

    /** Removes holidays more than a year old so the list stays short. */
    fun pruneOld(c: Context) {
        val cutoff = LocalDate.now().minusYears(1)
        if (holidays.keys.any { it.isBefore(cutoff) }) setHolidays(c, holidays.filterKeys { !it.isBefore(cutoff) })
    }

    /** For unit tests only. */
    fun setForTest(off: OffDays, hol: Set<LocalDate>) { offDays = off; holidays = hol.associateWith { "" } }

    fun set(c: Context, value: OffDays) {
        prefs(c).edit().putString("off", value.name).apply()
        offDays = value
    }

    fun isWorkingDay(d: LocalDate): Boolean = d !in holidays && when (d.dayOfWeek) {
        DayOfWeek.SUNDAY -> false
        DayOfWeek.SATURDAY -> when (offDays) {
            OffDays.SAT_SUN -> false
            OffDays.SUN -> true
            OffDays.SUN_2ND_4TH_SAT -> {
                val n = (d.dayOfMonth - 1) / 7 + 1 // which Saturday of the month
                n != 2 && n != 4
            }
        }
        else -> true
    }
}
