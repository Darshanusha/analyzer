package com.stock.analyzer.analysis.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.stock.analyzer.fundamental.domain.FinancialReport;
import com.stock.analyzer.marketdata.domain.DailyPriceBar;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicAnalysisEngineTests {

	private final DeterministicAnalysisEngine engine = new DeterministicAnalysisEngine();

	@Test
	void calculatesTechnicalEvidenceFromEodHistory() {
		List<DailyPriceBar> prices = new ArrayList<>();
		LocalDate start = LocalDate.of(2025, 1, 1);
		for (int index = 0; index < 220; index++) {
			BigDecimal close = BigDecimal.valueOf(100 + index * 0.25);
			prices.add(new DailyPriceBar(start.plusDays(index), close.subtract(BigDecimal.ONE),
					close.add(BigDecimal.ONE), close.subtract(BigDecimal.TWO), close,
					BigDecimal.valueOf(1_000_000 + index), close, close, start.plusDays(index)));
		}

		var result = engine.technical(prices);

		assertThat(result.status()).isEqualTo("AVAILABLE");
		assertThat(result.metrics()).containsKeys("sma20", "sma50", "sma200", "rsi14", "return1d",
				"return20d", "annualizedVolatility20", "atr14", "relativeVolume20");
		assertThat(result.reasons()).anyMatch(reason -> reason.contains("above its 200-session average"));
	}

	@Test
	void calculatesPointInTimeFundamentalGrowth() {
		FinancialReport latest = report(LocalDate.of(2026, 2, 1), LocalDate.of(2025, 12, 31), "120", "18");
		FinancialReport prior = report(LocalDate.of(2025, 2, 1), LocalDate.of(2024, 12, 31), "100", "12");

		var result = engine.fundamental(List.of(latest, prior));

		assertThat(result.status()).isEqualTo("AVAILABLE");
		assertThat(result.metrics()).containsEntry("revenueGrowth", new BigDecimal("0.20000000"));
		assertThat(result.metrics()).containsEntry("netIncomeGrowth", new BigDecimal("0.50000000"));
		assertThat(result.evidence()).containsExactly("Sharadar ART report available on 2026-02-01");
	}

	@Test
	void refusesTechnicalAnalysisWithTooLittleHistory() {
		var result = engine.technical(List.of(new DailyPriceBar(LocalDate.of(2026, 1, 2),
				BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
				BigDecimal.ONE, BigDecimal.ONE, LocalDate.of(2026, 1, 2))));

		assertThat(result.status()).isEqualTo("UNAVAILABLE");
		assertThat(result.limitations()).anyMatch(value -> value.contains("At least 50"));
	}

	private FinancialReport report(LocalDate providerDate, LocalDate reportPeriod, String revenue, String netIncome) {
		return new FinancialReport("ART", reportPeriod, providerDate, reportPeriod, "FY", providerDate,
				new BigDecimal(revenue), new BigDecimal("60"), new BigDecimal("25"), new BigDecimal(netIncome),
				new BigDecimal("30"), new BigDecimal("15"), new BigDecimal("24"), new BigDecimal("-9"),
				new BigDecimal("300"), new BigDecimal("120"), new BigDecimal("180"), new BigDecimal("40"),
				new BigDecimal("50"), new BigDecimal("10"), new BigDecimal("1.80"), new BigDecimal("0.50"),
				new BigDecimal("0.15"), new BigDecimal("0.20"), new BigDecimal("0.10"), new BigDecimal("0.12"),
				new BigDecimal("1.50"), new BigDecimal("0.28"), new BigDecimal("22"), new BigDecimal("14"),
				new BigDecimal("3"), new BigDecimal("400"), Map.of());
	}
}
