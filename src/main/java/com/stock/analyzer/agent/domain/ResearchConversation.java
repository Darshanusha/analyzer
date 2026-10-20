package com.stock.analyzer.agent.domain;

import java.time.Instant;
import java.util.UUID;

public record ResearchConversation(
		UUID conversationId,
		String title,
		String status,
		String summary,
		int messageCount,
		int summarizedMessageCount,
		int summaryVersion,
		Instant createdAt,
		Instant updatedAt) { }
