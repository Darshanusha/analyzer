package com.stock.analyzer.provider;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.stock.analyzer.configuration.ConcurrencyProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalRequestExecutorTests {

	@Test
	void boundsConcurrentOpenAiRequests() throws Exception {
		ConcurrencyProperties properties = properties();
		properties.setOpenAiMaxConcurrentRequests(2);
		properties.setTransientMaxRetries(0);
		ExternalRequestExecutor requests = new ExternalRequestExecutor(properties, new SimpleMeterRegistry());
		AtomicInteger active = new AtomicInteger();
		AtomicInteger maximum = new AtomicInteger();
		CountDownLatch firstWave = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);

		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<CompletableFuture<Integer>> tasks = java.util.stream.IntStream.range(0, 6)
					.mapToObj(index -> CompletableFuture.supplyAsync(() -> requests.openAi("test", () -> {
						int now = active.incrementAndGet();
						maximum.accumulateAndGet(now, Math::max);
						firstWave.countDown();
						try { release.await(); }
						catch (InterruptedException exception) {
							Thread.currentThread().interrupt();
							throw new IllegalStateException(exception);
						}
						finally { active.decrementAndGet(); }
						return index;
					}), executor)).toList();

			assertThat(firstWave.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(maximum).hasValue(2);
			release.countDown();
			CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).join();
			assertThat(maximum).hasValue(2);
		}
	}

	@Test
	void retriesTransientConnectionFailuresWithinConfiguredBudget() {
		ConcurrencyProperties properties = properties();
		properties.setTransientMaxRetries(2);
		properties.setInitialRetryDelay(Duration.ofMillis(1));
		properties.setMaximumRetryDelay(Duration.ofMillis(10));
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		ExternalRequestExecutor requests = new ExternalRequestExecutor(properties, meters);
		AtomicInteger attempts = new AtomicInteger();

		String result = requests.openAi("test", () -> {
			if (attempts.incrementAndGet() < 3) throw new ResourceAccessException("temporary timeout");
			return "ok";
		});

		assertThat(result).isEqualTo("ok");
		assertThat(attempts).hasValue(3);
		assertThat(meters.counter("stock.analysis.external.request.retries",
				"provider", "openai", "operation", "test").count()).isEqualTo(2.0);
	}

	private ConcurrencyProperties properties() {
		ConcurrencyProperties properties = new ConcurrencyProperties();
		properties.setPermitTimeout(Duration.ofSeconds(2));
		return properties;
	}
}
