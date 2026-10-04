package com.stock.analyzer.forecast.domain;

import java.time.Instant;

public record LlmInformationGapSummary(
		String fingerprint,
		String category,
		String requestedData,
		String reason,
		String importance,
		String preferredSourceType,
		String status,
		long occurrenceCount,
		Instant firstRequestedAt,
		Instant lastRequestedAt,
		String resolutionNotes) {
}
