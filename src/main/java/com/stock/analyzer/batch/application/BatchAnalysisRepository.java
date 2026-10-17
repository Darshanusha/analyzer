package com.stock.analyzer.batch.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.stock.analyzer.batch.domain.BatchAnalysisItemState;
import com.stock.analyzer.batch.domain.BatchAnalysisState;

public interface BatchAnalysisRepository {

	UUID create(List<String> symbols, boolean filter);

	default UUID create(List<String> symbols) {
		return create(symbols, true);
	}

	void markRunning(UUID batchAnalysisId);

	void linkAnalysis(UUID batchAnalysisId, String symbol, UUID analysisRunId);

	void completeItem(UUID batchAnalysisId, String symbol, boolean reusedExistingAnalysis);

	default void completeItem(UUID batchAnalysisId, String symbol) {
		completeItem(batchAnalysisId, symbol, false);
	}

	void failItem(UUID batchAnalysisId, String symbol, String errorCode, String errorMessage);

	void refreshSummary(UUID batchAnalysisId, boolean finished);

	Optional<BatchAnalysisState> find(UUID batchAnalysisId);

	List<BatchAnalysisItemState> findItems(UUID batchAnalysisId);
}
