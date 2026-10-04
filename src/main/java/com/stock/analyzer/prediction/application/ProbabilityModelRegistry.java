package com.stock.analyzer.prediction.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.stock.analyzer.configuration.PredictionProperties;
import com.stock.analyzer.prediction.domain.FeatureSnapshot;
import com.stock.analyzer.prediction.domain.LoadedModelInference;
import com.stock.analyzer.prediction.domain.ModelInference;
import com.stock.analyzer.prediction.domain.ProbabilityModelArtifact;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProbabilityModelRegistry {

	private final PredictionProperties properties;
	private final ObjectMapper objectMapper;
	private final Counter successCounter;
	private final Counter unavailableCounter;
	private final Counter failureCounter;

	private volatile CachedModel cached;

	public ProbabilityModelRegistry(PredictionProperties properties, ObjectMapper objectMapper,
			MeterRegistry meterRegistry) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.successCounter = meterRegistry.counter("stock.prediction.inference", "status", "success");
		this.unavailableCounter = meterRegistry.counter("stock.prediction.inference", "status", "unavailable");
		this.failureCounter = meterRegistry.counter("stock.prediction.inference", "status", "failure");
	}

	public LoadedModelInference predict(FeatureSnapshot snapshot) {
		if (!"COMPLETE".equals(snapshot.completenessStatus())) {
			unavailableCounter.increment();
			return unavailable("FEATURES_INCOMPLETE");
		}
		try {
			CachedModel model = load();
			if (model == null) {
				unavailableCounter.increment();
				return unavailable("MODEL_NOT_AVAILABLE");
			}
			ProbabilityModelArtifact artifact = model.artifact();
			if (!FeatureEngineeringService.FEATURE_SET_VERSION.equals(artifact.featureSetVersion())
					|| !FeatureEngineeringService.FEATURE_NAMES.equals(artifact.featureNames())) {
				failureCounter.increment();
				return unavailable("MODEL_INCOMPATIBLE");
			}
			double logit = artifact.intercept();
			for (String feature : artifact.featureNames()) {
				double raw = snapshot.features().get(feature);
				double mean = required(artifact.means(), feature);
				double scale = required(artifact.scales(), feature);
				double coefficient = required(artifact.coefficients(), feature);
				if (scale <= 0 || !Double.isFinite(scale)) throw new IllegalStateException("Invalid feature scale");
				logit += ((raw - mean) / scale) * coefficient;
			}
			double calibratedLogit = artifact.calibrationSlope() * logit + artifact.calibrationIntercept();
			double probability = 1.0 / (1.0 + Math.exp(-Math.max(-35, Math.min(35, calibratedLogit))));
			probability = Math.max(0.000001, Math.min(0.999999, probability));
			successCounter.increment();
			return new LoadedModelInference(new ModelInference(probability, "AVAILABLE", artifact.modelVersion(),
					artifact.calibrationVersion()), artifact, model.path(), model.sha256());
		}
		catch (RuntimeException | IOException exception) {
			failureCounter.increment();
			return unavailable("MODEL_LOAD_FAILED");
		}
	}

	private LoadedModelInference unavailable(String status) {
		return new LoadedModelInference(new ModelInference(null, status, null, null), null, null, null);
	}

	private synchronized CachedModel load() throws IOException {
		Path path = properties.getModelPath().toAbsolutePath().normalize();
		if (!Files.isRegularFile(path)) return null;
		long modified = Files.getLastModifiedTime(path).toMillis();
		long size = Files.size(path);
		CachedModel current = cached;
		if (current != null && current.path().equals(path) && current.modified() == modified && current.size() == size) {
			return current;
		}
		String json = Files.readString(path, StandardCharsets.UTF_8);
		ProbabilityModelArtifact artifact;
		try {
			artifact = objectMapper.readValue(json, ProbabilityModelArtifact.class);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Champion model artifact is invalid", exception);
		}
		validate(artifact);
		CachedModel loaded = new CachedModel(path, modified, size, artifact, sha256(json));
		cached = loaded;
		return loaded;
	}

	private void validate(ProbabilityModelArtifact artifact) {
		if (artifact.schemaVersion() != 1 || artifact.modelVersion() == null
				|| artifact.calibrationVersion() == null || artifact.featureNames() == null
				|| artifact.means() == null || artifact.scales() == null || artifact.coefficients() == null
				|| artifact.trainingRows() <= 0) {
			throw new IllegalStateException("Champion model artifact is incomplete");
		}
	}

	private double required(java.util.Map<String, Double> values, String feature) {
		Double value = values.get(feature);
		if (value == null || !Double.isFinite(value)) throw new IllegalStateException("Missing model feature " + feature);
		return value;
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private record CachedModel(Path path, long modified, long size, ProbabilityModelArtifact artifact, String sha256) {
	}
}
