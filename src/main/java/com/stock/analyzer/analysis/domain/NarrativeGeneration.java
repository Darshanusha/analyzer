package com.stock.analyzer.analysis.domain;

public record NarrativeGeneration(
		NarrativeOutput output,
		String source,
		String provider,
		String model,
		String status,
		Integer inputTokens,
		Integer outputTokens,
		Integer totalTokens,
		long durationMs,
		String errorCode) {
}
