package com.stock.analyzer.batch.domain;

import java.time.Instant;
import java.util.UUID;

public record BatchAnalysisState(
		UUID batchAnalysisId,
		String status,
		int totalItems,
		int completedItems,
		int successfulItems,
		int failedItems,
		Instant createdAt,
		Instant completedAt) {
}
