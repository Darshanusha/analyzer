package com.stock.analyzer.forecast.api;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.validation.Valid;

import com.stock.analyzer.configuration.LlmSelectionProperties;
import com.stock.analyzer.forecast.application.LlmSelectionService;
import com.stock.analyzer.forecast.application.LlmSelectionSubmission;
import com.stock.analyzer.forecast.domain.LlmSelectionState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class LlmSelectionController {

	private final LlmSelectionService service;
	private final LlmSelectionProperties properties;

	public LlmSelectionController(LlmSelectionService service, LlmSelectionProperties properties) {
		this.service = service;
		this.properties = properties;
	}

	@PostMapping("/batches/{batchAnalysisId}/llm-selections")
	public ResponseEntity<LlmSelectionState> create(@PathVariable UUID batchAnalysisId,
			@Valid @RequestBody CreateLlmSelectionRequest request) throws InterruptedException {
		LlmSelectionSubmission submission = service.submit(batchAnalysisId,
				request.resolvedHorizonTradingSessions(), request.resolvedForceReselection());
		if (submission.reusedExistingSelection()) {
			return ResponseEntity.ok().header("X-Selection-Reused", "true").body(submission.completion().join());
		}
		if (!request.resolvedWaitForCompletion()) {
			return ResponseEntity.accepted().header("X-Selection-Reused", "false")
					.body(service.get(submission.selectionId()));
		}

		int requestedWait = request.maximumWaitSeconds() == null
				? properties.getDefaultWaitSeconds() : request.maximumWaitSeconds();
		int waitSeconds = Math.max(1, Math.min(requestedWait, properties.getMaxWaitSeconds()));
		try {
			return ResponseEntity.ok().header("X-Selection-Reused", "false")
					.body(submission.completion().get(waitSeconds, TimeUnit.SECONDS));
		}
		catch (TimeoutException exception) {
			return ResponseEntity.accepted().header("X-Selection-Reused", "false")
					.body(service.get(submission.selectionId()));
		}
		catch (ExecutionException exception) {
			return ResponseEntity.ok().header("X-Selection-Reused", "false")
					.body(service.get(submission.selectionId()));
		}
	}

	@GetMapping("/llm-selections/{selectionId}")
	public LlmSelectionState get(@PathVariable UUID selectionId) {
		return service.get(selectionId);
	}
}
