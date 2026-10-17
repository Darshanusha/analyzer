package com.stock.analyzer.forecast.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.stock.analyzer.forecast.domain.LlmSelectionCall;
import com.stock.analyzer.forecast.domain.LlmSelectionCandidateInput;
import com.stock.analyzer.forecast.domain.LlmSelectionCandidateResult;
import com.stock.analyzer.forecast.domain.LlmSelectionOutput;
import com.stock.analyzer.forecast.domain.LlmSelectionState;

public interface LlmSelectionRepository {

	UUID create(UUID batchAnalysisId, int horizon, int candidateLimit, int selectionLimit,
			String model, String promptVersion);

	void markRunning(UUID selectionId);

	List<LlmSelectionCandidateInput> loadCandidates(UUID batchAnalysisId, int horizon, int limit);

	void complete(UUID selectionId, Map<String, Object> inputSnapshot, String inputSha256,
			LlmSelectionCall call, List<LlmSelectionCandidateResult> candidates, long durationMs);

	void fail(UUID selectionId, String errorCode, String errorMessage, long durationMs);

	Optional<LlmSelectionState> find(UUID selectionId);

	Optional<LlmSelectionState> findReusable(UUID batchAnalysisId, int horizon, int candidateLimit,
			int selectionLimit, String model, String promptVersion);
}
