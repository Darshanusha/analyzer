package com.stock.analyzer.forecast.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LlmSelectionState(
		UUID selectionId,
		UUID batchAnalysisId,
		String status,
		int horizonTradingSessions,
		int candidateLimit,
		int selectionLimit,
		int candidateCount,
		int selectedCount,
		String scoreInterpretation,
		String model,
		String promptVersion,
		Long inputTokens,
		Long outputTokens,
		Long totalTokens,
		Long durationMs,
		String errorCode,
		String errorMessage,
		boolean reusedExistingSelection,
		Instant createdAt,
		Instant completedAt,
		List<LlmSelectionCandidateResult> candidates,
		List<String> portfolioLevelRisks,
		List<String> overallLimitations,
		List<LlmSelectionWebResearch> webResearch) {
	public LlmSelectionState {
		candidates = candidates == null ? List.of() : List.copyOf(candidates);
		portfolioLevelRisks = portfolioLevelRisks == null ? List.of() : List.copyOf(portfolioLevelRisks);
		overallLimitations = overallLimitations == null ? List.of() : List.copyOf(overallLimitations);
		webResearch = webResearch == null ? List.of() : List.copyOf(webResearch);
	}
}
