package com.stock.analyzer.analysis.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.validation.Valid;

import com.stock.analyzer.analysis.application.AnalysisService;
import com.stock.analyzer.analysis.application.AnalysisSubmission;
import com.stock.analyzer.analysis.domain.AnalysisRunState;
import com.stock.analyzer.configuration.AnalysisProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AnalysisController {

	private final AnalysisService analysisService;
	private final AnalysisProperties properties;

	public AnalysisController(AnalysisService analysisService, AnalysisProperties properties) {
		this.analysisService = analysisService;
		this.properties = properties;
	}

	@PostMapping("/analyses")
	public ResponseEntity<?> create(@Valid @RequestBody CreateAnalysisRequest request) throws InterruptedException {
		AnalysisSubmission submission = analysisService.submit(request.symbol());
		boolean wait = request.waitForCompletion() == null || request.waitForCompletion();
		if (!wait) {
			return ResponseEntity.accepted().body(analysisService.get(submission.analysisRunId()));
		}

		int requestedWait = request.maximumWaitSeconds() == null
				? properties.getDefaultWaitSeconds() : request.maximumWaitSeconds();
		int waitSeconds = Math.min(requestedWait, properties.getMaxWaitSeconds());
		try {
			return ResponseEntity.ok(submission.completion().get(waitSeconds, TimeUnit.SECONDS));
		}
		catch (TimeoutException exception) {
			return ResponseEntity.accepted().body(analysisService.get(submission.analysisRunId()));
		}
		catch (ExecutionException exception) {
			return ResponseEntity.ok(analysisService.get(submission.analysisRunId()));
		}
	}

	@GetMapping("/analyses/{analysisRunId}")
	public AnalysisRunState get(@PathVariable UUID analysisRunId) {
		return analysisService.get(analysisRunId);
	}

	@GetMapping("/instruments/{symbol}/analyses")
	public List<AnalysisRunState> history(@PathVariable String symbol,
			@RequestParam(defaultValue = "20") int limit) {
		return analysisService.history(symbol, limit);
	}
}
