package com.stock.analyzer.forecast.domain;

import java.util.List;

public record LlmForecastCall(
		LlmForecastOutput output,
		String model,
		Long inputTokens,
		Long outputTokens,
		Long totalTokens,
		List<LlmHorizonForecastOutput> forecasts,
		List<AiInteractionAudit> interactions) {

	public LlmForecastCall {
		forecasts = forecasts == null ? List.of() : List.copyOf(forecasts);
		interactions = interactions == null ? List.of() : List.copyOf(interactions);
	}

	public LlmForecastCall(LlmForecastOutput output, String model, Long inputTokens,
			Long outputTokens, Long totalTokens) {
		this(output, model, inputTokens, outputTokens, totalTokens, List.of(), List.of());
	}
}
