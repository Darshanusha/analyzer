package com.stock.analyzer.forecast.domain;

import java.util.List;
import java.util.Map;

public record LlmSelectionCall(
		LlmSelectionOutput output,
		String model,
		long inputTokens,
		long outputTokens,
		long totalTokens,
		List<AiInteractionAudit> interactions,
		List<LlmSelectionWebResearch> webResearch,
		Map<String, Object> inputSnapshot) {
	public LlmSelectionCall {
		interactions = interactions == null ? List.of() : List.copyOf(interactions);
		webResearch = webResearch == null ? List.of() : List.copyOf(webResearch);
		inputSnapshot = inputSnapshot == null ? Map.of() : Map.copyOf(inputSnapshot);
	}
}
