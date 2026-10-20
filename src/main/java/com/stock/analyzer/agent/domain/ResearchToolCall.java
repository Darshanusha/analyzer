package com.stock.analyzer.agent.domain;

public record ResearchToolCall(
		String callId,
		String toolName,
		String argumentsJson,
		String resultJson,
		String status,
		long durationMs,
		Integer resultCount,
		String errorCode,
		String errorMessage) { }
