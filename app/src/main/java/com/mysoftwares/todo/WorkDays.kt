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

    /** Public / office holidays added by the user. */
    @Volatile var holidays: Set<LocalDate> = emptySet()
        private set

    private fun prefs(c: Context) =
        c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("workdays", Context.MODE_PRIVATE)

    fun load(c: Context) {
        offDays = runCatching { OffDays.valueOf(prefs(c).getString("off", null) ?: "") }.getOrDefault(OffDays.SAT_SUN)
        holidays = prefs(c).getStringSet("holidays", emptySet())!!
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.toSet()
    }

    fun setHolidays(c: Context, dates: Set<LocalDate>) {
        prefs(c).edit().putStringSet("holidays", dates.map { it.toString() }.toSet()).apply()
        holidays = dates
    }

    /** For unit tests only. */
    fun setForTest(off: OffDays, hol: Set<LocalDate>) { offDays = off; holidays = hol }

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
