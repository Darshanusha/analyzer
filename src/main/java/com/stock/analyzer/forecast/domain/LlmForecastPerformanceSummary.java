package com.stock.analyzer.forecast.domain;

public record LlmForecastPerformanceSummary(
		long scoredForecasts,
		Double directionalAccuracy,
		Double averageUpScore,
		Double actualUpRate,
		long scoredTouchForecasts,
		Double averageUpsideTouchScore,
		Double actualUpsideTouchRate,
		Double upsideTouchBrierScore,
		Double averageDownsideTouchScore,
		Double actualDownsideTouchRate,
		Double downsideTouchBrierScore) {
}
