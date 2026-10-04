package com.stock.analyzer.forecast.application;

import java.util.List;

import com.stock.analyzer.forecast.domain.LlmInformationGapSummary;
import com.stock.analyzer.forecast.domain.LlmInformationRequestAudit;

public interface LlmInformationGapRepository {

	void record(LlmInformationRequestAudit request);

	List<LlmInformationGapSummary> find(String status, int limit);

	boolean update(String fingerprint, String status, String resolutionNotes);
}
