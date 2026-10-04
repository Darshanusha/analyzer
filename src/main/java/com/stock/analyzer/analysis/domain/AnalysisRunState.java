package com.stock.analyzer.analysis.domain;

import java.time.Instant;
import java.util.UUID;

public record AnalysisRunState(
		UUID analysisRunId,
		String status,
		String symbol,
		Instant requestedAt,
		Instant startedAt,
		Instant completedAt,
		AnalysisReport report,
		String errorCode,
		String errorMessage) {
}
