package com.stock.analyzer.forecast.domain;

import java.util.List;

public record LlmSelectionOutputCandidate(
		int rank,
		String symbol,
		int selectionScore,
		String confidence,
		String comparativeThesis,
		List<String> bullishEvidence,
		List<String> bearishEvidence,
		List<String> comparativeAdvantages,
		List<String> invalidationRisks,
		List<String> dataLimitations) {
	public LlmSelectionOutputCandidate {
		bullishEvidence = copy(bullishEvidence);
		bearishEvidence = copy(bearishEvidence);
		comparativeAdvantages = copy(comparativeAdvantages);
		invalidationRisks = copy(invalidationRisks);
		dataLimitations = copy(dataLimitations);
	}

	private static List<String> copy(List<String> values) {
		return values == null ? List.of() : List.copyOf(values);
	}
}
