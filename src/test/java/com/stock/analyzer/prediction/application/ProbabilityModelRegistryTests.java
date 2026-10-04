package com.stock.analyzer.prediction.application;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stock.analyzer.configuration.PredictionProperties;
import com.stock.analyzer.prediction.domain.FeatureSnapshot;
import com.stock.analyzer.prediction.domain.ProbabilityModelArtifact;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ProbabilityModelRegistryTests {

	@TempDir
	Path temporaryDirectory;

	@Test
	void loadsCompatibleChampionAndProducesCalibratedProbability() throws Exception {
		Path champion = temporaryDirectory.resolve("champion.json");
		Map<String, Double> zeroes = values(0.0);
		Map<String, Double> ones = values(1.0);
		var artifact = new ProbabilityModelArtifact(1, "model-v1", "phase2-v1", "calibration-v1",
				"STANDARDIZED_LOGISTIC_REGRESSION_WITH_PLATT_CALIBRATION", Instant.parse("2026-10-03T00:00:00Z"),
				LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1), 10_000,
				FeatureEngineeringService.FEATURE_NAMES, zeroes, ones, zeroes, 0.0, 1.0, 0.0, 0.5, Map.of());
		var objectMapper = JsonMapper.builder().findAndAddModules().build();
		Files.writeString(champion, objectMapper.writeValueAsString(artifact));
		PredictionProperties properties = new PredictionProperties();
		properties.setModelPath(champion);
		var registry = new ProbabilityModelRegistry(properties, objectMapper, new SimpleMeterRegistry());
		var snapshot = new FeatureSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5), "phase2-v1", "COMPLETE",
				zeroes, List.of());

		var result = registry.predict(snapshot);

		assertThat(result.inference().status()).isEqualTo("AVAILABLE");
		assertThat(result.inference().probabilityUp()).isEqualTo(0.5);
		assertThat(result.artifactSha256()).hasSize(64);
	}

	@Test
	void withholdsProbabilityWhenChampionDoesNotExist() {
		PredictionProperties properties = new PredictionProperties();
		properties.setModelPath(temporaryDirectory.resolve("missing.json"));
		var registry = new ProbabilityModelRegistry(properties,
				JsonMapper.builder().findAndAddModules().build(), new SimpleMeterRegistry());
		var snapshot = new FeatureSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5), "phase2-v1", "COMPLETE",
				values(0.0), List.of());

		assertThat(registry.predict(snapshot).inference().status()).isEqualTo("MODEL_NOT_AVAILABLE");
	}

	private Map<String, Double> values(double value) {
		Map<String, Double> values = new LinkedHashMap<>();
		FeatureEngineeringService.FEATURE_NAMES.forEach(name -> values.put(name, value));
		return Map.copyOf(values);
	}
}
