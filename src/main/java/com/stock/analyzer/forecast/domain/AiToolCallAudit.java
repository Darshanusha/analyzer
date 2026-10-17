package com.stock.analyzer.forecast.domain;

public record AiToolCallAudit(String callId, String toolType, String toolName, String status,
		long durationMs, Integer resultCount, String argumentsSha256, String errorCode, String errorMessage) { }
