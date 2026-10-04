package com.stock.analyzer.prediction.application;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PredictionPerformanceMetrics {

	private final PredictionRepository repository;
	private final AtomicLong scoredOutcomes = new AtomicLong();
	private final AtomicReference<Double> accuracy = new AtomicReference<>(Double.NaN);
	private final AtomicReference<Double> brierScore = new AtomicReference<>(Double.NaN);
	private final AtomicReference<Double> averageProbability = new AtomicReference<>(Double.NaN);
	private final AtomicReference<Double> actualUpRate = new AtomicReference<>(Double.NaN);

	public PredictionPerformanceMetrics(PredictionRepository repository, MeterRegistry registry) {
		this.repository = repository;
		Gauge.builder("stock.prediction.scored.outcomes", scoredOutcomes, AtomicLong::doubleValue).register(registry);
		Gauge.builder("stock.prediction.accuracy", accuracy, value -> value.get()).register(registry);
		Gauge.builder("stock.prediction.brier.score", brierScore, value -> value.get()).register(registry);
		Gauge.builder("stock.prediction.average.probability", averageProbability, value -> value.get()).register(registry);
		Gauge.builder("stock.prediction.actual.up.rate", actualUpRate, value -> value.get()).register(registry);
	}

	@PostConstruct
	void initialize() {
		refresh();
	}

	@Scheduled(initialDelayString = "${prediction.metrics-initial-delay:10s}",
			fixedDelayString = "${prediction.metrics-refresh:60s}")
	void refresh() {
		var summary = repository.performanceSummary();
		scoredOutcomes.set(summary.scoredOutcomes());
		accuracy.set(value(summary.accuracy()));
		brierScore.set(value(summary.brierScore()));
		averageProbability.set(value(summary.averageProbability()));
		actualUpRate.set(value(summary.actualUpRate()));
	}

	private double value(Double value) {
		return value == null ? Double.NaN : value;
	}
}
