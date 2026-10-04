package com.stock.analyzer.forecast.domain;

import java.util.List;

public record LlmWebResearchResult(
		String status,
		String summary,
		List<LlmWebSource> sources,
		String model,
		Long inputTokens,
		Long outputTokens,
		Long totalTokens,
		long durationMs,
		String errorCode,
		String errorMessage) {

	public LlmWebResearchResult {
		sources = sources == null ? List.of() : List.copyOf(sources);
	}
}
