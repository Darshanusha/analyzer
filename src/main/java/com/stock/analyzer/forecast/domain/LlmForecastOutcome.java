package com.stock.analyzer.forecast.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record LlmForecastOutcome(
		LocalDate actualSession,
		BigDecimal cutoffAdjustedClose,
		BigDecimal actualAdjustedClose,
		boolean actualUp,
		Boolean predictedUp,
		Boolean correct,
		Instant resolvedAt) {
}
