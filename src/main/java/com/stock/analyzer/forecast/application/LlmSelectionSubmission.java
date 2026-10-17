package com.stock.analyzer.forecast.application;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.stock.analyzer.forecast.domain.LlmSelectionState;

public record LlmSelectionSubmission(
		UUID selectionId,
		CompletableFuture<LlmSelectionState> completion,
		boolean reusedExistingSelection) {
}
