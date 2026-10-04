package com.stock.analyzer.prediction.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FeatureSnapshot(
		UUID id,
		UUID analysisRunId,
		UUID instrumentId,
		LocalDate cutoffDate,
		LocalDate targetSession,
		String featureSetVersion,
		String completenessStatus,
		Map<String, Double> features,
		List<String> missingFeatures) {
}
