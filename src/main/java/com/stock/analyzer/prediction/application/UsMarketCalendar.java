package com.stock.analyzer.prediction.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.TemporalAdjusters;

import org.springframework.stereotype.Component;

@Component
public class UsMarketCalendar {

	public LocalDate nextTradingSession(LocalDate date) {
		LocalDate candidate = date.plusDays(1);
		while (!isRegularTradingDay(candidate)) candidate = candidate.plusDays(1);
		return candidate;
	}

	boolean isRegularTradingDay(LocalDate date) {
		DayOfWeek day = date.getDayOfWeek();
		if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
		int year = date.getYear();
		return !date.equals(observed(LocalDate.of(year, Month.JANUARY, 1)))
				&& !date.equals(nthWeekday(year, Month.JANUARY, DayOfWeek.MONDAY, 3))
				&& !date.equals(nthWeekday(year, Month.FEBRUARY, DayOfWeek.MONDAY, 3))
				&& !date.equals(easterSunday(year).minusDays(2))
				&& !date.equals(LocalDate.of(year, Month.MAY, 1)
						.with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY)))
				&& (year < 2022 || !date.equals(observed(LocalDate.of(year, Month.JUNE, 19))))
				&& !date.equals(observed(LocalDate.of(year, Month.JULY, 4)))
				&& !date.equals(nthWeekday(year, Month.SEPTEMBER, DayOfWeek.MONDAY, 1))
				&& !date.equals(nthWeekday(year, Month.NOVEMBER, DayOfWeek.THURSDAY, 4))
				&& !date.equals(observed(LocalDate.of(year, Month.DECEMBER, 25)));
	}

	private LocalDate nthWeekday(int year, Month month, DayOfWeek day, int ordinal) {
		return LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(ordinal, day));
	}

	private LocalDate observed(LocalDate holiday) {
		return switch (holiday.getDayOfWeek()) {
			case SATURDAY -> holiday.minusDays(1);
			case SUNDAY -> holiday.plusDays(1);
			default -> holiday;
		};
	}

	private LocalDate easterSunday(int year) {
		int a = year % 19;
		int b = year / 100;
		int c = year % 100;
		int d = b / 4;
		int e = b % 4;
		int f = (b + 8) / 25;
		int g = (b - f + 1) / 3;
		int h = (19 * a + b - d - g + 15) % 30;
		int i = c / 4;
		int k = c % 4;
		int l = (32 + 2 * e + 2 * i - h - k) % 7;
		int m = (a + 11 * h + 22 * l) / 451;
		int month = (h + l - 7 * m + 114) / 31;
		int day = (h + l - 7 * m + 114) % 31 + 1;
		return LocalDate.of(year, month, day);
	}
}
