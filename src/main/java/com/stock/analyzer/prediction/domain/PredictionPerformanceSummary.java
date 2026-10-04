package com.stock.analyzer.prediction.domain;

public record PredictionPerformanceSummary(
		long scoredOutcomes,
		Double accuracy,
		Double brierScore,
		Double averageProbability,
		Double actualUpRate) {
}
