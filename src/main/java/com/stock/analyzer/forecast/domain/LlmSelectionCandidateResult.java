package com.stock.analyzer.forecast.domain;

import java.util.List;
import java.util.UUID;

public record LlmSelectionCandidateResult(
		UUID forecastId,
		String symbol,
		int originalRank,
		int originalUpScore,
		String originalConfidence,
		boolean selected,
		Integer selectedRank,
		Integer selectionScore,
		String confidence,
		String comparativeThesis,
		List<String> bullishEvidence,
		List<String> bearishEvidence,
		List<String> comparativeAdvantages,
		List<String> invalidationRisks,
		List<String> dataLimitations,
		String exclusionReason) {
}
