package com.stock.analyzer.forecast.domain;

import java.util.List;

public record LlmSelectionOutput(
		List<LlmSelectionOutputCandidate> selectedCandidates,
		List<LlmSelectionExcludedCandidate> notSelected,
		List<String> portfolioLevelRisks,
		List<String> overallLimitations) {
	public LlmSelectionOutput {
		selectedCandidates = selectedCandidates == null ? List.of() : List.copyOf(selectedCandidates);
		notSelected = notSelected == null ? List.of() : List.copyOf(notSelected);
		portfolioLevelRisks = portfolioLevelRisks == null ? List.of() : List.copyOf(portfolioLevelRisks);
		overallLimitations = overallLimitations == null ? List.of() : List.copyOf(overallLimitations);
	}
}
