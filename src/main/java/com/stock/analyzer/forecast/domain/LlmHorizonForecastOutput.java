package com.stock.analyzer.forecast.domain;

import java.util.List;

public record LlmHorizonForecastOutput(int horizonTradingSessions, int upScore, int upsideTouchScore,
		int downsideTouchScore, String confidence,
		String thesis, List<String> bullishEvidence, List<String> bearishEvidence,
		List<String> risks, List<String> dataLimitations) {
	public LlmForecastOutput asForecastOutput() {
		return new LlmForecastOutput(upScore, upsideTouchScore, downsideTouchScore, confidence, thesis,
				bullishEvidence, bearishEvidence, risks, dataLimitations);
	}

	public LlmHorizonForecastOutput(int horizonTradingSessions, int upScore, String confidence, String thesis,
			List<String> bullishEvidence, List<String> bearishEvidence, List<String> risks,
			List<String> dataLimitations) {
		this(horizonTradingSessions, upScore, upScore, 100 - upScore, confidence, thesis, bullishEvidence,
				bearishEvidence, risks, dataLimitations);
	}
}
