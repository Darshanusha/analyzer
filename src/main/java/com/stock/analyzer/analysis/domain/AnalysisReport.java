package com.stock.analyzer.analysis.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.stock.analyzer.forecast.domain.LlmForecastResult;

public record AnalysisReport(
		UUID analysisRunId,
		String status,
		String symbol,
		String companyName,
		Instant analysisCutoff,
		LocalDate priceCutoffDate,
		Double probabilityUp,
		String probabilityStatus,
		LlmForecastResult llmForecast,
		String dataQualityStatus,
		List<AnalysisComponentResult> components,
		NarrativeOutput narrative,
		String narrativeSource) {
}
