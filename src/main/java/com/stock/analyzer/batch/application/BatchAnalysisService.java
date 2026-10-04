package com.stock.analyzer.batch.application;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import com.stock.analyzer.analysis.application.AnalysisService;
import com.stock.analyzer.analysis.application.AnalysisSubmission;
import com.stock.analyzer.api.error.ApiException;
import com.stock.analyzer.batch.domain.BatchAnalysisItemState;
import com.stock.analyzer.batch.domain.BatchAnalysisState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class BatchAnalysisService {

	private static final Logger log = LoggerFactory.getLogger(BatchAnalysisService.class);

	private final BatchAnalysisRepository repository;
	private final AnalysisService analysisService;
	private final ExecutorService executor;

	public BatchAnalysisService(BatchAnalysisRepository repository, AnalysisService analysisService,
			ExecutorService executor) {
		this.repository = repository;
		this.analysisService = analysisService;
		this.executor = executor;
	}

	public BatchAnalysisState submit(List<String> symbols) {
		UUID batchId = repository.create(symbols);
		java.util.concurrent.CompletableFuture.runAsync(() -> process(batchId, symbols), executor);
		return get(batchId);
	}

	public BatchAnalysisState get(UUID batchId) {
		return repository.find(batchId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"BATCH_NOT_FOUND", "No batch analysis was found for " + batchId));
	}

	public List<BatchAnalysisItemState> items(UUID batchId) {
		get(batchId);
		return repository.findItems(batchId);
	}

	public BatchAnalysisState retryFailed(UUID batchId) {
		BatchAnalysisState original = get(batchId);
		if ("QUEUED".equals(original.status()) || "RUNNING".equals(original.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "BATCH_NOT_FINISHED",
					"Failed items cannot be retried while the original batch is still running");
		}
		List<String> failedSymbols = repository.findItems(batchId).stream()
				.filter(item -> "FAILED".equals(item.status()))
				.map(BatchAnalysisItemState::symbol)
				.toList();
		if (failedSymbols.isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "BATCH_HAS_NO_FAILED_ITEMS",
					"The batch has no failed items to retry");
		}
		BatchAnalysisState retry = submit(failedSymbols);
		log.info("Created retry batch: originalBatchId={}, retryBatchId={}, failedItems={}",
				batchId, retry.batchAnalysisId(), failedSymbols.size());
		return retry;
	}

	private void process(UUID batchId, List<String> symbols) {
		log.info("Starting batch analysis: batchId={}, items={}", batchId, symbols.size());
		repository.markRunning(batchId);
		for (String symbol : symbols) {
			try {
				AnalysisSubmission submission = analysisService.submit(symbol);
				repository.linkAnalysis(batchId, symbol, submission.analysisRunId());
				submission.completion().join();
				repository.completeItem(batchId, symbol);
			}
			catch (RuntimeException exception) {
				Throwable cause = exception instanceof CompletionException && exception.getCause() != null
						? exception.getCause() : exception;
				String errorCode = errorCode(cause);
				String errorMessage = safeMessage(cause);
				repository.failItem(batchId, symbol, errorCode, errorMessage);
				log.warn("Batch item failed: batchId={}, symbol={}, errorCode={}, errorMessage={}",
						batchId, symbol, errorCode, errorMessage);
			}
			repository.refreshSummary(batchId, false);
		}
		repository.refreshSummary(batchId, true);
		BatchAnalysisState completed = get(batchId);
		log.info("Completed batch analysis: batchId={}, status={}, successfulItems={}, failedItems={}",
				batchId, completed.status(), completed.successfulItems(), completed.failedItems());
	}

	private String errorCode(Throwable exception) {
		return exception instanceof ApiException apiException
				? apiException.code() : exception.getClass().getSimpleName();
	}

	private String safeMessage(Throwable exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
		return message.substring(0, Math.min(1_000, message.length()));
	}
}
