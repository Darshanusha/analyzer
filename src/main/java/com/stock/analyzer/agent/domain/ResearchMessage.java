package com.stock.analyzer.agent.domain;

import java.time.Instant;
import java.util.UUID;

public record ResearchMessage(
		UUID messageId,
		UUID conversationId,
		int sequenceNumber,
		String role,
		String content,
		String openAiResponseId,
		String model,
		Long inputTokens,
		Long outputTokens,
		Long totalTokens,
		Long durationMs,
		Instant createdAt) { }
