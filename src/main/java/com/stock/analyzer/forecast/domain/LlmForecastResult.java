package com.stock.analyzer.forecast.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LlmForecastResult(
		UUID forecastId,
		UUID analysisRunId,
		UUID instrumentId,
		String symbol,
		LocalDate cutoffDate,
		LocalDate targetSession,
		String status,
		String direction,
		Integer upScore,
		String confidence,
		String thesis,
		List<String> bullishEvidence,
		List<String> bearishEvidence,
		List<String> risks,
		List<String> dataLimitations,
		String scoreInterpretation,
		String provider,
		String model,
		String promptVersion,
		String inputSha256,
		Long inputTokens,
		Long outputTokens,
		Long totalTokens,
		long durationMs,
		String errorCode,
		String errorMessage,
		Instant createdAt,
		LlmForecastOutcome outcome) {

	public LlmForecastResult {
		bullishEvidence = copy(bullishEvidence);
		bearishEvidence = copy(bearishEvidence);
		risks = copy(risks);
		dataLimitations = copy(dataLimitations);
	}

	private static List<String> copy(List<String> values) {
		return values == null ? List.of() : List.copyOf(values);
	}
}
