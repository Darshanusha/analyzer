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
		Instant resolvedAt,
		Boolean upsideTargetTouched,
		Boolean downsideTargetTouched,
		LocalDate upsideFirstTouchDate,
		LocalDate downsideFirstTouchDate,
		String touchSequence,
		BigDecimal maximumAdjustedHigh,
		BigDecimal minimumAdjustedLow,
		BigDecimal maximumReturnPercent,
		BigDecimal maximumDrawdownPercent,
		BigDecimal horizonReturnPercent) {

	public LlmForecastOutcome(LocalDate actualSession, BigDecimal cutoffAdjustedClose,
			BigDecimal actualAdjustedClose, boolean actualUp, Boolean predictedUp, Boolean correct,
			Instant resolvedAt) {
		this(actualSession, cutoffAdjustedClose, actualAdjustedClose, actualUp, predictedUp, correct,
				resolvedAt, null, null, null, null, null, null, null, null, null, null);
	}
}
