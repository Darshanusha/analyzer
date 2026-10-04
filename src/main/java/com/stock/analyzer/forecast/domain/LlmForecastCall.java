package com.stock.analyzer.forecast.domain;

public record LlmForecastCall(
		LlmForecastOutput output,
		String model,
		Long inputTokens,
		Long outputTokens,
		Long totalTokens) {
}
