package com.stock.analyzer.forecast.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stock.analyzer.forecast.domain.BatchLlmRankingItem;
import com.stock.analyzer.forecast.domain.LlmForecastPerformanceSummary;
import com.stock.analyzer.forecast.domain.LlmForecastResult;
import com.stock.analyzer.forecast.domain.AiInteractionAudit;

public interface LlmForecastRepository {

	void save(LlmForecastResult result, Map<String, Object> inputSnapshot);

	void saveInteractions(UUID analysisRunId, List<AiInteractionAudit> interactions);

	int resolveOutcomes(UUID instrumentId);

	List<LlmForecastResult> findBySymbol(String symbol, int limit);

	List<LlmForecastResult> findByAnalysisRun(UUID analysisRunId);

	List<BatchLlmRankingItem> findBatchRankings(UUID batchAnalysisId, int horizonTradingSessions);

	default List<BatchLlmRankingItem> findBatchRankings(UUID batchAnalysisId) {
		return findBatchRankings(batchAnalysisId, 1);
	}

	LlmForecastPerformanceSummary performanceSummary();
}
