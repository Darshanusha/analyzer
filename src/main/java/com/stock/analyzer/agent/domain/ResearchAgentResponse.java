package com.stock.analyzer.agent.domain;

import java.util.List;

public record ResearchAgentResponse(
		String responseId,
		String model,
		String answer,
		Long inputTokens,
		Long outputTokens,
		Long totalTokens,
		long durationMs,
		List<ResearchToolCall> toolCalls) {
	public ResearchAgentResponse {
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
	}
}
