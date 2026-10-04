package com.stock.analyzer.prediction.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.stock.analyzer.instrument.domain.Instrument;
import com.stock.analyzer.marketdata.domain.DailyPriceBar;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureEngineeringServiceTests {

	private final UsMarketCalendar calendar = new UsMarketCalendar();
	private final FeatureEngineeringService service = new FeatureEngineeringService(calendar);

	@Test
	void buildsTheVersionedFeatureContractWithoutFuturePrices() {
		LocalDate start = LocalDate.of(2025, 1, 2);
		List<DailyPriceBar> stock = prices(start, 80, 100.0, 0.35);
		List<DailyPriceBar> market = prices(start, 80, 400.0, 0.20);
		LocalDate cutoff = start.plusDays(70);
		Instrument instrument = new Instrument(UUID.randomUUID(), "AAPL", "Apple", "NASDAQ", "USD",
				true, "Technology", "Consumer Electronics", "199059", "stocks", cutoff);

		var snapshot = service.create(UUID.randomUUID(), instrument, cutoff, stock, market);

		assertThat(snapshot.featureSetVersion()).isEqualTo("phase2-v1");
		assertThat(snapshot.completenessStatus()).isEqualTo("COMPLETE");
		assertThat(snapshot.features().keySet()).containsExactlyInAnyOrderElementsOf(FeatureEngineeringService.FEATURE_NAMES);
		assertThat(snapshot.targetSession()).isAfter(cutoff);
	}

	@Test
	void marksSnapshotIncompleteWhenMarketHistoryIsMissing() {
		LocalDate cutoff = LocalDate.of(2026, 10, 2);
		Instrument instrument = new Instrument(UUID.randomUUID(), "AAPL", "Apple", "NASDAQ", "USD",
				true, "Technology", "Consumer Electronics", "199059", "stocks", cutoff);

		var snapshot = service.create(UUID.randomUUID(), instrument, cutoff,
				prices(LocalDate.of(2026, 1, 1), 100, 100, 0.2), List.of());

		assertThat(snapshot.completenessStatus()).isEqualTo("INCOMPLETE");
		assertThat(snapshot.missingFeatures()).contains("market_return_20d", "relative_strength_market_20d");
	}

	@Test
	void skipsWeekendsAndRegularExchangeHolidays() {
		assertThat(calendar.nextTradingSession(LocalDate.of(2026, 7, 2)))
				.isEqualTo(LocalDate.of(2026, 7, 6));
		assertThat(calendar.nextTradingSession(LocalDate.of(2026, 11, 25)))
				.isEqualTo(LocalDate.of(2026, 11, 27));
	}

	private List<DailyPriceBar> prices(LocalDate start, int count, double initial, double increment) {
		List<DailyPriceBar> prices = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			BigDecimal close = BigDecimal.valueOf(initial + index * increment);
			prices.add(new DailyPriceBar(start.plusDays(index), close, close.add(BigDecimal.ONE),
					close.subtract(BigDecimal.ONE), close, BigDecimal.valueOf(1_000_000 + index * 1_000L),
					close, close, start.plusDays(index)));
		}
		return prices;
	}
}
