package com.stock.analyzer.analysis.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAnalysisRequest(
		@NotBlank @Size(max = 15) String symbol,
		Boolean waitForCompletion,
		@Min(1) @Max(120) Integer maximumWaitSeconds) {
}
