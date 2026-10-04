package com.stock.analyzer.forecast.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stock.analyzer.forecast.domain.BatchLlmRankingItem;
import com.stock.analyzer.forecast.domain.LlmForecastPerformanceSummary;
import com.stock.analyzer.forecast.domain.LlmForecastResult;

public interface LlmForecastRepository {

	void save(LlmForecastResult result, Map<String, Object> inputSnapshot);

	int resolveOutcomes(UUID instrumentId);

	List<LlmForecastResult> findBySymbol(String symbol, int limit);

	List<BatchLlmRankingItem> findBatchRankings(UUID batchAnalysisId);

	LlmForecastPerformanceSummary performanceSummary();
}
