package com.stock.analyzer.agent.domain;

public record ResearchSummaryResult(
		String responseId,
		String model,
		String summary,
		Long inputTokens,
		Long outputTokens,
		Long totalTokens,
		long durationMs) { }
