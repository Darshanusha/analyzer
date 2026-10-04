package com.stock.analyzer.forecast.domain;

public record LlmInformationRequest(
		String category,
		String dataRequested,
		String reason,
		String importance,
		String preferredSourceType,
		String searchQuery) {
}
