package com.stock.analyzer.forecast.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import com.stock.analyzer.batch.application.BatchAnalysisService;
import com.stock.analyzer.batch.domain.BatchAnalysisState;
import com.stock.analyzer.forecast.application.LlmForecastService;
import com.stock.analyzer.forecast.domain.BatchLlmRankingResponse;
import com.stock.analyzer.forecast.domain.LlmForecastPerformanceSummary;
import com.stock.analyzer.forecast.domain.LlmForecastResult;
import com.stock.analyzer.forecast.domain.LlmInformationGapSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class LlmForecastController {

	private final LlmForecastService forecastService;
	private final BatchAnalysisService batchAnalysisService;

	public LlmForecastController(LlmForecastService forecastService, BatchAnalysisService batchAnalysisService) {
		this.forecastService = forecastService;
		this.batchAnalysisService = batchAnalysisService;
	}

	@GetMapping("/instruments/{symbol}/llm-forecasts")
	public List<LlmForecastResult> history(@PathVariable String symbol,
			@RequestParam(defaultValue = "20") int limit) {
		return forecastService.history(symbol, limit);
	}

	@GetMapping("/batches/{batchAnalysisId}/llm-rankings")
	public BatchLlmRankingResponse rankings(@PathVariable UUID batchAnalysisId,
			@RequestParam(defaultValue = "1") int horizon) {
		BatchAnalysisState batch = batchAnalysisService.get(batchAnalysisId);
		return forecastService.rankings(batchAnalysisId, batch.status(), horizon);
	}

	@GetMapping("/llm-forecasts/performance")
	public LlmForecastPerformanceSummary performance() {
		return forecastService.performanceSummary();
	}

	@GetMapping("/llm-information-gaps")
	public List<LlmInformationGapSummary> informationGaps(
			@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "100") int limit) {
		return forecastService.informationGaps(status, limit);
	}

	@PatchMapping("/llm-information-gaps/{fingerprint}")
	public void updateInformationGap(@PathVariable String fingerprint,
			@Valid @RequestBody UpdateInformationGapRequest request) {
		forecastService.updateInformationGap(fingerprint, request.status(), request.resolutionNotes());
	}
}
