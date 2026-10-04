package com.stock.analyzer.forecast.domain;

import java.util.List;
import java.util.UUID;

public record BatchLlmRankingResponse(
		UUID batchAnalysisId,
		String batchStatus,
		String scoreType,
		String disclaimer,
		List<BatchLlmRankingItem> rankings) {
}
