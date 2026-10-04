package com.stock.analyzer.analysis.domain;

import java.util.List;
import java.util.Map;

public record AnalysisComponentResult(
		String key,
		String version,
		String status,
		String summary,
		Map<String, Object> metrics,
		List<String> reasons,
		List<String> evidence,
		List<String> limitations,
		long durationMs) {
}
