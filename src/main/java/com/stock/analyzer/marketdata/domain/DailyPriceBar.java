package com.stock.analyzer.marketdata.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyPriceBar(
		LocalDate date,
		BigDecimal open,
		BigDecimal high,
		BigDecimal low,
		BigDecimal close,
		BigDecimal volume,
		BigDecimal fullyAdjustedClose,
		BigDecimal unadjustedClose,
		LocalDate providerLastUpdated) {
}
