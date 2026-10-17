package com.stock.analyzer.forecast.domain;

import java.util.List;

public record LlmCandidateFilterDecision(
		boolean eligible,
		String method,
		int positiveSignalCount,
		int technicalSignalCount,
		Double probabilityUp,
		List<String> reasons) {

	public LlmCandidateFilterDecision {
		reasons = reasons == null ? List.of() : List.copyOf(reasons);
	}
}
