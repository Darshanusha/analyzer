package com.stock.analyzer.forecast.domain;

import java.util.List;

public record LlmForecastOutput(
		int upScore,
		int upsideTouchScore,
		int downsideTouchScore,
		String confidence,
		String thesis,
		List<String> bullishEvidence,
		List<String> bearishEvidence,
		List<String> risks,
		List<String> dataLimitations,
		List<LlmInformationRequest> informationRequests) {

	public LlmForecastOutput {
		bullishEvidence = copy(bullishEvidence);
		bearishEvidence = copy(bearishEvidence);
		risks = copy(risks);
		dataLimitations = copy(dataLimitations);
		informationRequests = informationRequests == null ? List.of() : List.copyOf(informationRequests);
	}

	public LlmForecastOutput(int upScore, String confidence, String thesis, List<String> bullishEvidence,
			List<String> bearishEvidence, List<String> risks, List<String> dataLimitations) {
		this(upScore, upScore, 100 - upScore, confidence, thesis, bullishEvidence, bearishEvidence, risks,
				dataLimitations, List.of());
	}

	public LlmForecastOutput(int upScore, int upsideTouchScore, int downsideTouchScore, String confidence,
			String thesis, List<String> bullishEvidence, List<String> bearishEvidence, List<String> risks,
			List<String> dataLimitations) {
		this(upScore, upsideTouchScore, downsideTouchScore, confidence, thesis, bullishEvidence, bearishEvidence,
				risks, dataLimitations, List.of());
	}

	public LlmForecastOutput(int upScore, String confidence, String thesis, List<String> bullishEvidence,
			List<String> bearishEvidence, List<String> risks, List<String> dataLimitations,
			List<LlmInformationRequest> informationRequests) {
		this(upScore, upScore, 100 - upScore, confidence, thesis, bullishEvidence, bearishEvidence, risks,
				dataLimitations, informationRequests);
	}

	private static List<String> copy(List<String> values) {
		return values == null ? List.of() : List.copyOf(values);
	}
}
