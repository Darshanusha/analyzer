package com.stock.analyzer.forecast.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public record LlmSelectionCandidateInput(
		UUID forecastId,
		UUID analysisRunId,
		UUID instrumentId,
		int originalRank,
		String symbol,
		String stockName,
		String sector,
		LocalDate cutoffDate,
		LocalDate targetSession,
		Instant informationCutoff,
		int upScore,
		String confidence,
		String thesis,
		String dataQualityStatus,
		String model,
		String promptVersion,
		Map<String, Object> inputSnapshot,
		Map<String, Object> responsePayload) {
}
