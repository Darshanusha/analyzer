package com.stock.analyzer.news.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record NewsArticle(
		String providerArticleId,
		Instant publishedAt,
		Instant updatedAt,
		String title,
		String teaser,
		String body,
		String url,
		String author,
		List<String> symbols,
		List<String> channels,
		List<String> tags,
		Map<String, Object> rawData) {
}
