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
		LlmForecastOutcome outcome,
		int horizonTradingSessions,
		Instant informationCutoff,
		Double upsideThresholdPercent,
		Double downsideThresholdPercent,
		Integer upsideTouchScore,
		Integer downsideTouchScore,
		Integer touchScoreSpread) {

	public LlmForecastResult {
		bullishEvidence = copy(bullishEvidence);
		bearishEvidence = copy(bearishEvidence);
		risks = copy(risks);
		dataLimitations = copy(dataLimitations);
	}

	public LlmForecastResult(UUID forecastId, UUID analysisRunId, UUID instrumentId, String symbol,
			LocalDate cutoffDate, LocalDate targetSession, String status, String direction, Integer upScore,
			String confidence, String thesis, List<String> bullishEvidence, List<String> bearishEvidence,
			List<String> risks, List<String> dataLimitations, String scoreInterpretation, String provider,
			String model, String promptVersion, String inputSha256, Long inputTokens, Long outputTokens,
			Long totalTokens, long durationMs, String errorCode, String errorMessage, Instant createdAt,
			LlmForecastOutcome outcome) {
		this(forecastId, analysisRunId, instrumentId, symbol, cutoffDate, targetSession, status, direction,
				upScore, confidence, thesis, bullishEvidence, bearishEvidence, risks, dataLimitations,
				scoreInterpretation, provider, model, promptVersion, inputSha256, inputTokens, outputTokens,
				totalTokens, durationMs, errorCode, errorMessage, createdAt, outcome, 1, createdAt,
				null, null, null, null, null);
	}

	public LlmForecastResult(UUID forecastId, UUID analysisRunId, UUID instrumentId, String symbol,
			LocalDate cutoffDate, LocalDate targetSession, String status, String direction, Integer upScore,
			String confidence, String thesis, List<String> bullishEvidence, List<String> bearishEvidence,
			List<String> risks, List<String> dataLimitations, String scoreInterpretation, String provider,
			String model, String promptVersion, String inputSha256, Long inputTokens, Long outputTokens,
			Long totalTokens, long durationMs, String errorCode, String errorMessage, Instant createdAt,
			LlmForecastOutcome outcome, int horizonTradingSessions, Instant informationCutoff) {
		this(forecastId, analysisRunId, instrumentId, symbol, cutoffDate, targetSession, status, direction,
				upScore, confidence, thesis, bullishEvidence, bearishEvidence, risks, dataLimitations,
				scoreInterpretation, provider, model, promptVersion, inputSha256, inputTokens, outputTokens,
				totalTokens, durationMs, errorCode, errorMessage, createdAt, outcome, horizonTradingSessions,
				informationCutoff, null, null, null, null, null);
	}

	private static List<String> copy(List<String> values) {
		return values == null ? List.of() : List.copyOf(values);
	}
}
