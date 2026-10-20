package com.stock.analyzer.forecast.application;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically exposes resolved touch-target quality without putting database IDs in metric labels. */
@Component
public class LlmForecastPerformanceMetrics {

	private final LlmForecastRepository repository;
	private final AtomicLong scored = new AtomicLong();
	private final AtomicReference<Double> upsideRate = new AtomicReference<>(Double.NaN);
	private final AtomicReference<Double> downsideRate = new AtomicReference<>(Double.NaN);
	private final AtomicReference<Double> upsideBrier = new AtomicReference<>(Double.NaN);
	private final AtomicReference<Double> downsideBrier = new AtomicReference<>(Double.NaN);

	public LlmForecastPerformanceMetrics(LlmForecastRepository repository, MeterRegistry registry) {
		this.repository = repository;
		Gauge.builder("stock.analysis.llm.touch.scored", scored, AtomicLong::doubleValue).register(registry);
		Gauge.builder("stock.analysis.llm.touch.actual.rate", upsideRate, AtomicReference::get)
				.tag("target", "upside").register(registry);
		Gauge.builder("stock.analysis.llm.touch.actual.rate", downsideRate, AtomicReference::get)
				.tag("target", "downside").register(registry);
		Gauge.builder("stock.analysis.llm.touch.brier.score", upsideBrier, AtomicReference::get)
				.tag("target", "upside").register(registry);
		Gauge.builder("stock.analysis.llm.touch.brier.score", downsideBrier, AtomicReference::get)
				.tag("target", "downside").register(registry);
	}

	@PostConstruct
	void initialize() {
		refresh();
	}

	@Scheduled(initialDelayString = "${analysis.llm-forecast.metrics-initial-delay:10s}",
			fixedDelayString = "${analysis.llm-forecast.metrics-refresh:60s}")
	void refresh() {
		var summary = repository.performanceSummary();
		scored.set(summary.scoredTouchForecasts());
		upsideRate.set(value(summary.actualUpsideTouchRate()));
		downsideRate.set(value(summary.actualDownsideTouchRate()));
		upsideBrier.set(value(summary.upsideTouchBrierScore()));
		downsideBrier.set(value(summary.downsideTouchBrierScore()));
	}

	private double value(Double value) {
		return value == null ? Double.NaN : value;
	}
}
