package com.stock.analyzer.forecast.domain;

import java.util.List;

public record LlmHorizonForecastOutput(int horizonTradingSessions, int upScore, String confidence,
		String thesis, List<String> bullishEvidence, List<String> bearishEvidence,
		List<String> risks, List<String> dataLimitations) {
	public LlmForecastOutput asForecastOutput() {
		return new LlmForecastOutput(upScore, confidence, thesis, bullishEvidence, bearishEvidence, risks,
				dataLimitations);
	}
}
