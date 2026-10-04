package com.stock.analyzer.forecast.domain;

public record LlmForecastPerformanceSummary(
		long scoredForecasts,
		Double directionalAccuracy,
		Double averageUpScore,
		Double actualUpRate) {
}
