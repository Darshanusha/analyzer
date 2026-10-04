package com.stock.analyzer.prediction.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PredictionResult(
		UUID predictionId,
		UUID analysisRunId,
		String symbol,
		LocalDate cutoffDate,
		LocalDate targetSession,
		Double probabilityUp,
		String status,
		String modelVersion,
		String calibrationVersion,
		String featureSetVersion,
		String dataQualityStatus,
		Instant createdAt,
		PredictionOutcome outcome) {
}
