package com.stock.analyzer.provider;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

import com.stock.analyzer.configuration.ConcurrencyProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Global bounded concurrency and transient retry policy for paid external providers. */
@Component
public class ExternalRequestExecutor {

	private static final Logger log = LoggerFactory.getLogger(ExternalRequestExecutor.class);

	private final ConcurrencyProperties properties;
	private final MeterRegistry meters;
	private final Limiter openAi;
	private final Limiter selectionWebResearch;
	private final Limiter sharadar;

	public ExternalRequestExecutor(ConcurrencyProperties properties, MeterRegistry meters) {
		properties.validate();
		this.properties = properties;
		this.meters = meters;
		this.openAi = limiter("openai", properties.getOpenAiMaxConcurrentRequests());
		this.selectionWebResearch = limiter("selection_web_research",
				properties.getSelectionWebResearchMaxConcurrentRequests());
		this.sharadar = limiter("sharadar", properties.getSharadarMaxConcurrentRequests());
	}

	public <T> T openAi(String operation, Supplier<T> request) {
		return retry("openai", operation, () -> withPermit(openAi, request));
	}

	public <T> T selectionWebResearch(String operation, Supplier<T> request) {
		return retry("openai", operation,
				() -> withPermit(selectionWebResearch, () -> withPermit(openAi, request)));
	}

	public <T> T sharadar(String operation, Supplier<T> request) {
		return retry("sharadar", operation, () -> withPermit(sharadar, request));
	}

	private Limiter limiter(String provider, int permits) {
		Limiter limiter = new Limiter(new Semaphore(permits, true), new AtomicInteger(), new AtomicInteger());
		meters.gauge("stock.analysis.external.requests.in.flight", java.util.List.of(
				io.micrometer.core.instrument.Tag.of("provider", provider)), limiter.inFlight(), AtomicInteger::doubleValue);
		meters.gauge("stock.analysis.external.requests.queued", java.util.List.of(
				io.micrometer.core.instrument.Tag.of("provider", provider)), limiter.waiting(), AtomicInteger::doubleValue);
		return limiter;
	}

	private <T> T withPermit(Limiter limiter, Supplier<T> request) {
		boolean acquired = false;
		boolean waitingRecorded = true;
		limiter.waiting().incrementAndGet();
		try {
			acquired = limiter.semaphore().tryAcquire(properties.getPermitTimeout().toMillis(), TimeUnit.MILLISECONDS);
			limiter.waiting().decrementAndGet();
			waitingRecorded = false;
			if (!acquired) throw new IllegalStateException("Timed out waiting for external request capacity");
			limiter.inFlight().incrementAndGet();
			return request.get();
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for external request capacity", exception);
		}
		finally {
			if (waitingRecorded) limiter.waiting().decrementAndGet();
			if (acquired) {
				limiter.inFlight().decrementAndGet();
				limiter.semaphore().release();
			}
		}
	}

	private <T> T retry(String provider, String operation, Supplier<T> request) {
		int retries = properties.getTransientMaxRetries();
		for (int attempt = 0; ; attempt++) {
			long started = System.nanoTime();
			try {
				T result = request.get();
				record(provider, operation, "success", started);
				return result;
			}
			catch (RuntimeException exception) {
				if (attempt >= retries || !retryable(exception)) {
					record(provider, operation, "failure", started);
					throw exception;
				}
				Duration delay = retryDelay(exception, attempt);
				if (delay == null) {
					record(provider, operation, "failure", started);
					throw exception;
				}
				record(provider, operation, "retry", started);
				meters.counter("stock.analysis.external.request.retries", "provider", provider,
						"operation", operation).increment();
				log.warn("Retrying transient external request: provider={}, operation={}, attempt={}, delayMs={}, failureType={}",
						provider, operation, attempt + 1, delay.toMillis(), exception.getClass().getSimpleName());
				sleep(delay);
			}
		}
	}

	private boolean retryable(RuntimeException exception) {
		if (exception instanceof ResourceAccessException) return true;
		if (exception instanceof RestClientResponseException response) {
			int status = response.getStatusCode().value();
			return status == 429 || status == 503;
		}
		return false;
	}

	private Duration retryDelay(RuntimeException exception, int attempt) {
		Duration serverDelay = retryAfter(exception);
		if (serverDelay != null) {
			return serverDelay.compareTo(properties.getMaximumRetryDelay()) <= 0 ? serverDelay : null;
		}
		long base = properties.getInitialRetryDelay().toMillis();
		long maximum = properties.getMaximumRetryDelay().toMillis();
		long exponential = base * (1L << Math.min(attempt, 20));
		long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, Math.min(500L, base + 1L)));
		return Duration.ofMillis(Math.min(maximum, exponential + jitter));
	}

	private Duration retryAfter(RuntimeException exception) {
		if (!(exception instanceof RestClientResponseException response) || response.getResponseHeaders() == null) {
			return null;
		}
		String value = response.getResponseHeaders().getFirst("Retry-After");
		if (value == null || value.isBlank()) return null;
		try {
			return Duration.ofSeconds(Math.max(0, Long.parseLong(value.strip())));
		}
		catch (NumberFormatException ignored) {
			try {
				Duration delay = Duration.between(ZonedDateTime.now(),
						ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME));
				return delay.isNegative() ? Duration.ZERO : delay;
			}
			catch (DateTimeParseException invalidDate) { return null; }
		}
	}

	private void sleep(Duration delay) {
		try { Thread.sleep(delay); }
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while backing off an external request", exception);
		}
	}

	private void record(String provider, String operation, String outcome, long started) {
		long duration = System.nanoTime() - started;
		meters.counter("stock.analysis.external.requests", "provider", provider,
				"operation", operation, "outcome", outcome).increment();
		meters.timer("stock.analysis.external.request.duration", "provider", provider,
				"operation", operation, "outcome", outcome).record(duration, TimeUnit.NANOSECONDS);
	}

	private record Limiter(Semaphore semaphore, AtomicInteger waiting, AtomicInteger inFlight) { }
}
