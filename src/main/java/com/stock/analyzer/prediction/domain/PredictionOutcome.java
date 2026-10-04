package com.stock.analyzer.prediction.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PredictionOutcome(
		LocalDate actualSession,
		BigDecimal cutoffAdjustedClose,
		BigDecimal actualAdjustedClose,
		boolean actualUp,
		Boolean predictedUp,
		Boolean correct,
		Double brierComponent,
		Instant resolvedAt) {
}
