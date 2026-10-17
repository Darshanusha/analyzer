package com.stock.analyzer.forecast.domain;

import java.time.Instant;
import java.util.List;

public record LlmSelectionWebResearch(
		String symbol,
		Instant informationCutoff,
		String status,
		String responseId,
		String model,
		String summary,
		List<LlmWebSource> sources,
		Long inputTokens,
		Long outputTokens,
		Long totalTokens,
		long durationMs,
		int webSearchCount,
		String errorCode,
		String errorMessage) {

	public LlmSelectionWebResearch {
		sources = sources == null ? List.of() : List.copyOf(sources);
	}
}
