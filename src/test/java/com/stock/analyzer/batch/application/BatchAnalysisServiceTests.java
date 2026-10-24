package com.stock.analyzer.batch.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.stock.analyzer.analysis.application.AnalysisService;
import com.stock.analyzer.analysis.application.AnalysisSubmission;
import com.stock.analyzer.analysis.domain.AnalysisReport;
import com.stock.analyzer.api.error.ApiException;
import com.stock.analyzer.batch.domain.BatchAnalysisItemState;
import com.stock.analyzer.batch.domain.BatchAnalysisState;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BatchAnalysisServiceTests {

	private final BatchAnalysisRepository repository = mock(BatchAnalysisRepository.class);
	private final AnalysisService analysisService = mock(AnalysisService.class);
	private final ExecutorService executor = mock(ExecutorService.class);
	private final BatchAnalysisService service = new BatchAnalysisService(repository, analysisService, executor);

	@Test
	void processesIndependentBatchItemsInParallel() throws Exception {
		UUID batchId = UUID.randomUUID();
		List<String> symbols = List.of("AAPL", "MSFT", "NVDA", "AMZN");
		when(repository.create(symbols, true)).thenReturn(batchId);
		when(repository.find(batchId)).thenReturn(Optional.of(batch(batchId, "RUNNING", 4, 0, 0)));
		when(repository.findItems(batchId)).thenReturn(symbols.stream().map(symbol -> item(symbol, "COMPLETED")).toList());
		AtomicInteger active = new AtomicInteger();
		AtomicInteger maximum = new AtomicInteger();
		CountDownLatch firstWave = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch completed = new CountDownLatch(4);
		doAnswer(invocation -> { completed.countDown(); return null; })
				.when(repository).completeItem(any(UUID.class), anyString(), anyBoolean());
		when(analysisService.submit(anyString(), anyBoolean(), anyBoolean())).thenAnswer(invocation -> {
			int now = active.incrementAndGet();
			maximum.accumulateAndGet(now, Math::max);
			firstWave.countDown();
			CompletableFuture<AnalysisReport> completion = CompletableFuture.supplyAsync(() -> {
				try { release.await(); }
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
				finally { active.decrementAndGet(); }
				return mock(AnalysisReport.class);
			});
			return new AnalysisSubmission(UUID.randomUUID(), completion, false);
		});

		try (ExecutorService coordinator = Executors.newVirtualThreadPerTaskExecutor();
				ExecutorService itemExecutor = Executors.newFixedThreadPool(2)) {
			BatchAnalysisService parallelService = new BatchAnalysisService(repository, analysisService,
					coordinator, itemExecutor, new SimpleMeterRegistry());
			parallelService.submit(symbols, false, true);
			assertThat(firstWave.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(maximum).hasValue(2);
			release.countDown();
			assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
			verify(repository, timeout(2_000).times(4)).completeItem(any(UUID.class), anyString(), anyBoolean());
			assertThat(maximum).hasValue(2);
		}
	}

	@Test
	void retriesOnlyFailedItemsInANewBatch() {
		UUID originalId = UUID.randomUUID();
		UUID retryId = UUID.randomUUID();
		when(repository.find(originalId)).thenReturn(Optional.of(batch(originalId, "PARTIAL_SUCCESS", 2, 1, 1)));
		when(repository.findItems(originalId)).thenReturn(List.of(
				item("AAPL", "COMPLETED"), item("MSFT", "FAILED")));
		when(repository.create(List.of("MSFT"), true)).thenReturn(retryId);
		when(repository.find(retryId)).thenReturn(Optional.of(batch(retryId, "QUEUED", 1, 0, 0)));

		BatchAnalysisState result = service.retryFailed(originalId);

		assertThat(result.batchAnalysisId()).isEqualTo(retryId);
		assertThat(result.totalItems()).isEqualTo(1);
		verify(repository).create(List.of("MSFT"), true);
		verify(executor).execute(any(Runnable.class));
	}

	@Test
	void rejectsRetryWhileOriginalBatchIsRunning() {
		UUID batchId = UUID.randomUUID();
		when(repository.find(batchId)).thenReturn(Optional.of(batch(batchId, "RUNNING", 2, 1, 0)));

		assertThatThrownBy(() -> service.retryFailed(batchId))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("BATCH_NOT_FINISHED"));
	}

	@Test
	void rejectsRetryWhenThereAreNoFailedItems() {
		UUID batchId = UUID.randomUUID();
		when(repository.find(batchId)).thenReturn(Optional.of(batch(batchId, "COMPLETED", 1, 1, 0)));
		when(repository.findItems(batchId)).thenReturn(List.of(item("AAPL", "COMPLETED")));

		assertThatThrownBy(() -> service.retryFailed(batchId))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("BATCH_HAS_NO_FAILED_ITEMS"));
	}

	private BatchAnalysisState batch(UUID id, String status, int total, int completed, int failed) {
		return new BatchAnalysisState(id, status, total, completed, completed - failed, failed, true,
				Instant.parse("2026-10-03T00:00:00Z"), "RUNNING".equals(status) ? null : Instant.now());
	}

	private BatchAnalysisItemState item(String symbol, String status) {
		return new BatchAnalysisItemState(UUID.randomUUID(), symbol, status, null, false, null,
				"FAILED".equals(status) ? "TEST_FAILURE" : null,
				"FAILED".equals(status) ? "Test failure" : null,
				Instant.parse("2026-10-03T00:00:00Z"), Instant.parse("2026-10-03T00:01:00Z"));
	}
}
