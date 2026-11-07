package com.corridoriq.backend.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.corridoriq.backend.engine.BankCalendar.Closure;
import com.corridoriq.backend.engine.BankCalendar.HolidayRow;

class BankCalendarTest {

    private final BankCalendar cal = BankCalendar.bundled(Map.of(
            new Closure("SG", LocalDate.of(2025, 3, 14)), "Core banking upgrade"));

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    // ------------------------------------------------------------------ weekends
    @Test
    void usWeekendsAreSaturdayAndSunday() {
        assertThat(cal.nonBusiness("US", d("2026-04-25"))).isEqualTo("Saturday");
        assertThat(cal.nonBusiness("US", d("2026-04-26"))).isEqualTo("Sunday");
        assertThat(cal.nonBusiness("US", d("2026-04-27"))).isNull();   // Monday
    }

    @Test
    void gulfBanksWorkSundayAndRestFridayAndSaturday() {
        assertThat(cal.nonBusiness("BH", d("2026-04-24"))).isEqualTo("Friday · Gulf weekend");
        assertThat(cal.nonBusiness("SA", d("2026-04-25"))).isEqualTo("Saturday · Gulf weekend");
        assertThat(cal.nonBusiness("BH", d("2026-04-26"))).isNull();   // Sunday is a working day
    }

    @Test
    void indianBanksCloseOnTheSecondAndFourthSaturdayOnly() {
        assertThat(cal.nonBusiness("IN", d("2026-04-04"))).isNull();                                   // 1st Saturday
        assertThat(cal.nonBusiness("IN", d("2026-04-11"))).isEqualTo("2nd Saturday · Indian bank holiday");
        assertThat(cal.nonBusiness("IN", d("2026-04-18"))).isNull();                                   // 3rd Saturday
        assertThat(cal.nonBusiness("IN", d("2026-04-25"))).isEqualTo("4th Saturday · Indian bank holiday");
        assertThat(cal.nonBusiness("IN", d("2026-04-26"))).isEqualTo("Sunday");
    }

    // ------------------------------------------------------------------ holidays
    @Test
    void fedwireClosesOnAWeekdayHoliday() {
        assertThat(cal.nonBusiness("US", d("2026-12-25"))).contains("Christmas").contains("Fedwire closed");
    }

    @Test
    void aSundayHolidayClosesTheMondayButASaturdayHolidayDoesNotCloseTheFriday() {
        // 1 Jan 2023 was a Sunday: observed on Monday 2 Jan.
        assertThat(cal.nonBusiness("US", d("2023-01-02"))).contains("(observed)");
        // 4 Jul 2026 is a Saturday: the Federal Reserve stays open on Friday 3 Jul.
        assertThat(cal.nonBusiness("US", d("2026-07-03"))).isNull();
    }

    @Test
    void japaneseBanksCloseOnTheNewYearBankHolidays() {
        assertThat(cal.nonBusiness("JP", d("2026-01-02"))).isEqualTo("Bank holiday (New Year)");
        assertThat(cal.nonBusiness("JP", d("2026-12-31"))).isEqualTo("Bank holiday (year end)");
    }

    @Test
    void anAdHocClosureAppliesToOneBankNotTheWholeCountry() {
        LocalDate day = d("2025-03-14");   // a Friday
        assertThat(cal.nonBusiness("SG", day)).isNull();
        assertThat(cal.bankClosed("SG", "SG", day)).isEqualTo("Core banking upgrade");
        assertThat(cal.bankClosed("LN", "GB", day)).isNull();
    }

    // ------------------------------------------------------------------ bundled data
    @Test
    void bundledHolidaysCoverEveryCountryFrom2023To2027() {
        List<HolidayRow> rows = BankCalendar.bundledHolidays();
        assertThat(rows).hasSize(613);
        for (String country : Reference.COUNTRIES.keySet()) {
            int[] years = cal.holidayYears(country);
            assertThat(years).as(country).isNotNull();
            assertThat(years[0]).as(country + " first year").isEqualTo(2023);
            assertThat(years[1]).as(country + " last year").isEqualTo(2027);
        }
        Set<String> countries = rows.stream().map(HolidayRow::country).collect(Collectors.toSet());
        assertThat(countries).isEqualTo(Reference.COUNTRIES.keySet());
    }

    @Test
    void csvLinesSplitOnCommasOutsideQuotes() throws IOException {
        assertThat(BankCalendar.splitCsv("AE,2023-01-01,\"New Year's Day\",\"holidays 0.83\""))
                .containsExactly("AE", "2023-01-01", "New Year's Day", "holidays 0.83");
        assertThat(BankCalendar.splitCsv("XX,2023-01-01,\"Day, with comma and \"\"quote\"\"\",rule"))
                .containsExactly("XX", "2023-01-01", "Day, with comma and \"quote\"", "rule");

        List<HolidayRow> rows = BankCalendar.readHolidayCsv(new BufferedReader(new StringReader(
                "country_code,holiday_date,name,source\nGB,2026-12-25,\"Christmas Day\",\"holidays 0.83\"\n\n")));
        assertThat(rows).containsExactly(new HolidayRow("GB", d("2026-12-25"), "Christmas Day", "holidays 0.83"));
    }

    // ------------------------------------------------------------------ dates
    @Test
    void weekdaysAreNumberedFromSunday() {
        assertThat(Dates.portalDow(d("2026-04-26"))).isEqualTo(0);   // Sunday
        assertThat(Dates.portalDow(d("2026-04-27"))).isEqualTo(1);   // Monday
        assertThat(Dates.portalDow(d("2026-04-25"))).isEqualTo(6);   // Saturday
        assertThat(Dates.dayLabel(d("2025-10-09"))).isEqualTo("Thu 9 Oct 2025");
    }

    @Test
    void roundingMatchesPythonHalfEvenOnTheExactValue() {
        assertThat(Dates.round(2.675, 2)).isEqualTo(2.67);   // 2.675 is stored as 2.67499999...
        assertThat(Dates.round(0.125, 2)).isEqualTo(0.12);   // exact tie goes to the even digit
        assertThat(Dates.round(1234.5, 0)).isEqualTo(1234.0);
    }

    @Test
    void timestampsAreWholeSecondUtc() {
        assertThat(Dates.iso(java.time.ZonedDateTime.of(2026, 4, 1, 9, 1, 7, 900_000_000, ZoneOffset.UTC).toInstant()))
                .isEqualTo("2026-04-01T09:01:07Z");
        var t = java.time.Instant.parse("2026-04-01T00:00:00Z");
        assertThat(Dates.hoursBetween(t, Dates.plusHours(t, 1.5))).isEqualTo(1.5);
    }
}
