package com.stock.analyzer.batch.domain;

import java.time.Instant;
import java.util.UUID;

public record BatchAnalysisItemState(
		UUID itemId,
		String symbol,
		String status,
		UUID analysisRunId,
		String errorCode,
		String errorMessage,
		Instant createdAt,
		Instant completedAt) {
}
