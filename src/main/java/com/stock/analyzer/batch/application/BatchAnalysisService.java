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
import org.springframework.beans.factory.annotation.Autowired;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class BatchAnalysisService {

	private static final Logger log = LoggerFactory.getLogger(BatchAnalysisService.class);

	private final BatchAnalysisRepository repository;
	private final AnalysisService analysisService;
	private final ExecutorService executor;
	private final MeterRegistry meters;

	@Autowired
	public BatchAnalysisService(BatchAnalysisRepository repository, AnalysisService analysisService,
			ExecutorService executor, MeterRegistry meters) {
		this.repository = repository; this.analysisService = analysisService; this.executor = executor; this.meters = meters;
	}

	public BatchAnalysisService(BatchAnalysisRepository repository, AnalysisService analysisService,
			ExecutorService executor) {
		this(repository, analysisService, executor, null);
	}

	public BatchAnalysisState submit(List<String> symbols) {
		return submit(symbols, false, true);
	}

	public BatchAnalysisState submit(List<String> symbols, boolean forceReanalysis) {
		return submit(symbols, forceReanalysis, true);
	}

	public BatchAnalysisState submit(List<String> symbols, boolean forceReanalysis, boolean filter) {
		UUID batchId = repository.create(symbols, filter);
		java.util.concurrent.CompletableFuture.runAsync(
				() -> process(batchId, symbols, forceReanalysis, filter), executor);
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
		BatchAnalysisState retry = submit(failedSymbols, true, original.filter());
		log.info("Created retry batch: originalBatchId={}, retryBatchId={}, failedItems={}",
				batchId, retry.batchAnalysisId(), failedSymbols.size());
		return retry;
	}

	private void process(UUID batchId, List<String> symbols, boolean forceReanalysis, boolean filter) {
		log.info("Starting batch analysis: batchId={}, items={}, filter={}", batchId, symbols.size(), filter);
		repository.markRunning(batchId);
		for (String symbol : symbols) {
			try {
				AnalysisSubmission submission = analysisService.submit(symbol, forceReanalysis, filter);
				repository.linkAnalysis(batchId, symbol, submission.analysisRunId());
				submission.completion().join();
				repository.completeItem(batchId, symbol, submission.reusedExistingAnalysis());
				if (submission.reusedExistingAnalysis()) {
					log.info("Reused today's completed analysis for batch item: batchId={}, symbol={}, analysisRunId={}",
							batchId, symbol, submission.analysisRunId());
				}
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
		List<BatchAnalysisItemState> items = repository.findItems(batchId);
		long reused = items.stream().filter(BatchAnalysisItemState::reusedExistingAnalysis).count();
		long filtered = items.stream().filter(i -> "FILTERED_OUT".equals(i.llmForecastStatus())).count();
		long available = items.stream().filter(i -> "AVAILABLE".equals(i.llmForecastStatus())).count();
		long abstained = items.stream().filter(i -> "ABSTAINED".equals(i.llmForecastStatus())).count();
		log.info("Completed batch analysis: batchId={}, status={}, total={}, reused={}, passedFilter={}, filtered={}, "
				+ "abstained={}, llmSuccessful={}, failed={}", batchId, completed.status(), items.size(), reused,
				Math.max(0, items.size() - filtered), filtered, abstained, available, completed.failedItems());
		if (meters != null) {
			meters.counter("stock.analysis.batch.items", "outcome", "reused").increment(reused);
			meters.counter("stock.analysis.batch.items", "outcome", "filtered").increment(filtered);
			meters.counter("stock.analysis.batch.items", "outcome", "llm_available").increment(available);
			meters.counter("stock.analysis.batch.items", "outcome", "failed").increment(completed.failedItems());
		}
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
