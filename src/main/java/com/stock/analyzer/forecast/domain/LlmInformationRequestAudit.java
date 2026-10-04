package com.stock.analyzer.forecast.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LlmInformationRequestAudit(
		UUID id,
		UUID forecastId,
		UUID analysisRunId,
		UUID instrumentId,
		String symbol,
		LocalDate cutoffDate,
		LlmInformationRequest request,
		String fingerprint,
		String resolutionStatus,
		LlmWebResearchResult webResearch,
		Instant createdAt) {

	public List<LlmWebSource> sources() {
		return webResearch == null ? List.of() : webResearch.sources();
	}
}
