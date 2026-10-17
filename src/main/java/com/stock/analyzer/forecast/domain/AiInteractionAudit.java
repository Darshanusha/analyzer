package com.stock.analyzer.forecast.domain;

import java.util.List;

public record AiInteractionAudit(String responseId, String model, int roundNumber, String status,
		Long inputTokens, Long outputTokens, Long totalTokens, long durationMs,
		int toolCallCount, int webSearchCount, String errorCode, String errorMessage,
		List<AiToolCallAudit> toolCalls) {
	public AiInteractionAudit { toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls); }
}
