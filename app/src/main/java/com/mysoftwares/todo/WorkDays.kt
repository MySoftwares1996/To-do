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

    private fun prefs(c: Context) =
        c.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences("workdays", Context.MODE_PRIVATE)

    fun load(c: Context) {
        offDays = runCatching { OffDays.valueOf(prefs(c).getString("off", null) ?: "") }.getOrDefault(OffDays.SAT_SUN)
    }

    fun set(c: Context, value: OffDays) {
        prefs(c).edit().putString("off", value.name).apply()
        offDays = value
    }

    fun isWorkingDay(d: LocalDate): Boolean = when (d.dayOfWeek) {
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
