package com.stock.analyzer.agent.domain;

import java.util.List;
import java.util.UUID;

public record ResearchAgentTurn(
		UUID conversationId,
		ResearchMessage userMessage,
		ResearchMessage assistantMessage,
		List<ResearchToolCall> toolCalls,
		boolean summarized,
		int summaryVersion,
		int messageCount) {
	public ResearchAgentTurn {
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
	}
}
