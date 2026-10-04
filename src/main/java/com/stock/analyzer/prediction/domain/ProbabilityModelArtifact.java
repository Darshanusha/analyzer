package com.stock.analyzer.prediction.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record ProbabilityModelArtifact(
		int schemaVersion,
		String modelVersion,
		String featureSetVersion,
		String calibrationVersion,
		String algorithm,
		Instant trainedAt,
		LocalDate trainingStartDate,
		LocalDate trainingEndDate,
		int trainingRows,
		List<String> featureNames,
		Map<String, Double> means,
		Map<String, Double> scales,
		Map<String, Double> coefficients,
		double intercept,
		double calibrationSlope,
		double calibrationIntercept,
		double decisionThreshold,
		Map<String, Object> metrics) {
}
