package com.mysoftwares.todo

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/** Checks the date maths that decides when alarms ring. Runs on every build. */
class RecurrenceTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private lateinit var savedTz: TimeZone

    @Before fun setUp() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        WorkDays.setForTest(WorkDays.OffDays.SAT_SUN, emptySet())
    }

    @After fun tearDown() = TimeZone.setDefault(savedTz)

    private fun ms(y: Int, m: Int, d: Int, h: Int = 10, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun date(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()

    private fun occ(start: Long, r: Repeat, k: Long, rule: WorkdayRule = WorkdayRule.SAME_DAY) =
        date(Recurrence.adjust(Recurrence.occurrence(start, r, k), r, rule))

    // ---- repeats ----

    @Test fun daily() = assertEquals(LocalDateTime.of(2026, 12, 1, 10, 0), occ(ms(2026, 11, 30), Repeat.DAILY, 1))

    @Test fun weekly() = assertEquals(LocalDateTime.of(2026, 12, 4, 10, 0), occ(ms(2026, 11, 27), Repeat.WEEKLY, 1))

    @Test fun monthlyClampsWithoutDrift() {
        val start = ms(2027, 1, 31)
        assertEquals(LocalDate.of(2027, 2, 28), occ(start, Repeat.MONTHLY, 1).toLocalDate())
        assertEquals(LocalDate.of(2027, 3, 31), occ(start, Repeat.MONTHLY, 2).toLocalDate()) // back to 31st
        assertEquals(LocalDate.of(2027, 4, 30), occ(start, Repeat.MONTHLY, 3).toLocalDate())
    }

    @Test fun monthEndIncludingLeapYear() {
        val start = ms(2028, 1, 31)
        assertEquals(LocalDate.of(2028, 2, 29), occ(start, Repeat.MONTH_END, 1).toLocalDate())
        assertEquals(LocalDate.of(2028, 4, 30), occ(start, Repeat.MONTH_END, 3).toLocalDate())
    }

    @Test fun quarterly() {
        val start = ms(2027, 1, 15)
        assertEquals(LocalDate.of(2027, 4, 15), occ(start, Repeat.QUARTERLY, 1).toLocalDate())
        assertEquals(LocalDate.of(2027, 10, 15), occ(start, Repeat.QUARTERLY, 3).toLocalDate())
        val end31 = ms(2027, 1, 31)
        assertEquals(LocalDate.of(2027, 4, 30), occ(end31, Repeat.QUARTERLY, 1).toLocalDate())
        assertEquals(LocalDate.of(2027, 7, 31), occ(end31, Repeat.QUARTERLY, 2).toLocalDate())
    }

    @Test fun quarterEnd() {
        val start = ms(2027, 2, 10) // inside the Jan–Mar quarter
        assertEquals(LocalDate.of(2027, 3, 31), occ(start, Repeat.QUARTER_END, 0).toLocalDate())
        assertEquals(LocalDate.of(2027, 6, 30), occ(start, Repeat.QUARTER_END, 1).toLocalDate())
        assertEquals(LocalDate.of(2027, 9, 30), occ(start, Repeat.QUARTER_END, 2).toLocalDate())
        assertEquals(LocalDate.of(2027, 12, 31), occ(start, Repeat.QUARTER_END, 3).toLocalDate())
        assertEquals(LocalDate.of(2027, 6, 30), occ(ms(2027, 4, 1), Repeat.QUARTER_END, 0).toLocalDate())
    }

    @Test fun monthEndMinusDays() {
        val start = ms(2026, 11, 1)
        // Last day −2: Nov 28, Dec 29, Feb 26 (2027)
        assertEquals(LocalDate.of(2026, 11, 28), date(Recurrence.occurrence(start, Repeat.MONTH_END, 0, 2)).toLocalDate())
        assertEquals(LocalDate.of(2026, 12, 29), date(Recurrence.occurrence(start, Repeat.MONTH_END, 1, 2)).toLocalDate())
        assertEquals(LocalDate.of(2027, 2, 26), date(Recurrence.occurrence(start, Repeat.MONTH_END, 3, 2)).toLocalDate())
    }

    @Test fun quarterEndMinusDays() {
        // Last day of the quarter −3: 28 Mar, 27 Jun
        val start = ms(2027, 1, 10)
        assertEquals(LocalDate.of(2027, 3, 28), date(Recurrence.occurrence(start, Repeat.QUARTER_END, 0, 3)).toLocalDate())
        assertEquals(LocalDate.of(2027, 6, 27), date(Recurrence.occurrence(start, Repeat.QUARTER_END, 1, 3)).toLocalDate())
    }

    @Test fun monthEndMinusThenWorkingDay() {
        // Jul 2027: last day 31 (Sat), −1 = 30 Fri (working) → stays 30
        val start = ms(2027, 7, 1)
        val t = Recurrence.nextAfter(start, Repeat.MONTH_END, ms(2027, 7, 1), WorkdayRule.PREVIOUS, 1)!!
        assertEquals(LocalDate.of(2027, 7, 30), date(t).toLocalDate())
        // Aug 2027: last day 31 (Tue), −2 = 29 Sun → previous working day Fri 27
        val t2 = Recurrence.nextAfter(ms(2027, 8, 1), Repeat.MONTH_END, ms(2027, 8, 1), WorkdayRule.PREVIOUS, 2)!!
        assertEquals(LocalDate.of(2027, 8, 27), date(t2).toLocalDate())
    }

    @Test fun yearlyFinancialYearEnd() =
        assertEquals(LocalDate.of(2028, 3, 31), occ(ms(2027, 3, 31), Repeat.YEARLY, 1).toLocalDate())

    @Test fun yearlyFromLeapDay() =
        assertEquals(LocalDate.of(2029, 2, 28), occ(ms(2028, 2, 29), Repeat.YEARLY, 1).toLocalDate())

    @Test fun keepsTimeOfDay() =
        assertEquals(LocalDateTime.of(2027, 2, 28, 18, 30), occ(ms(2027, 1, 31, 18, 30), Repeat.MONTHLY, 1))

    @Test fun nextAfterSkipsPast() {
        val start = ms(2026, 1, 5)
        val next = Recurrence.nextAfter(start, Repeat.MONTHLY, ms(2026, 11, 27))!!
        assertEquals(LocalDate.of(2026, 12, 5), date(next).toLocalDate())
    }

    @Test fun oneTimeHasNoNext() = assertNull(Recurrence.nextAfter(ms(2026, 1, 1), Repeat.NONE, ms(2026, 1, 2)))

    // ---- working days ----

    @Test fun sundayMovesToFridayOrMonday() {
        val sun = ms(2027, 8, 15) // Sunday
        assertEquals(LocalDate.of(2027, 8, 13), occ(sun, Repeat.MONTHLY, 0, WorkdayRule.PREVIOUS).toLocalDate())
        assertEquals(LocalDate.of(2027, 8, 16), occ(sun, Repeat.MONTHLY, 0, WorkdayRule.NEXT).toLocalDate())
        assertEquals(LocalDate.of(2027, 8, 15), occ(sun, Repeat.MONTHLY, 0, WorkdayRule.SAME_DAY).toLocalDate())
    }

    @Test fun workingDayIsNotMoved() {
        val wed = ms(2027, 8, 18)
        assertEquals(LocalDate.of(2027, 8, 18), occ(wed, Repeat.MONTHLY, 0, WorkdayRule.PREVIOUS).toLocalDate())
    }

    @Test fun dailyIgnoresWorkdayRule() {
        val sun = ms(2027, 8, 15)
        assertEquals(LocalDate.of(2027, 8, 15), occ(sun, Repeat.DAILY, 0, WorkdayRule.NEXT).toLocalDate())
    }

    @Test fun sundayOnlyKeepsSaturday() {
        WorkDays.setForTest(WorkDays.OffDays.SUN, emptySet())
        val sun = ms(2027, 8, 15)
        assertEquals(LocalDate.of(2027, 8, 14), occ(sun, Repeat.MONTHLY, 0, WorkdayRule.PREVIOUS).toLocalDate())
    }

    @Test fun secondAndFourthSaturday() {
        WorkDays.setForTest(WorkDays.OffDays.SUN_2ND_4TH_SAT, emptySet())
        assertEquals(false, WorkDays.isWorkingDay(LocalDate.of(2027, 8, 14))) // 2nd Saturday
        assertEquals(false, WorkDays.isWorkingDay(LocalDate.of(2027, 8, 28))) // 4th Saturday
        assertEquals(true, WorkDays.isWorkingDay(LocalDate.of(2027, 8, 7)))   // 1st Saturday
        assertEquals(true, WorkDays.isWorkingDay(LocalDate.of(2027, 8, 21)))  // 3rd Saturday
        // 2nd Saturday → previous working day is Friday 13th
        assertEquals(LocalDate.of(2027, 8, 13), occ(ms(2027, 8, 14), Repeat.MONTHLY, 0, WorkdayRule.PREVIOUS).toLocalDate())
    }

    @Test fun holidaysAreSkipped() {
        WorkDays.setForTest(WorkDays.OffDays.SAT_SUN, setOf(LocalDate.of(2027, 10, 29))) // Friday holiday
        // Sunday 31 Oct → previous: Sat off, Fri holiday → Thursday 28th
        assertEquals(LocalDate.of(2027, 10, 28), occ(ms(2027, 10, 31), Repeat.MONTH_END, 0, WorkdayRule.PREVIOUS).toLocalDate())
        // Friday holiday itself → next working day Monday 1 Nov
        assertEquals(LocalDate.of(2027, 11, 1), occ(ms(2027, 10, 29), Repeat.MONTHLY, 0, WorkdayRule.NEXT).toLocalDate())
    }

    @Test fun monthEndOnWeekendPreviousWorkingDay() {
        // 31 Jul 2027 is a Saturday → Friday 30th
        assertEquals(LocalDate.of(2027, 7, 30), occ(ms(2027, 7, 31), Repeat.MONTH_END, 0, WorkdayRule.PREVIOUS).toLocalDate())
    }
}
