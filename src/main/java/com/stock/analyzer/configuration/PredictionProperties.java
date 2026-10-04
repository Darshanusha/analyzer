package com.stock.analyzer.configuration;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("prediction")
public class PredictionProperties {

	private Path modelPath = Path.of("model-artifacts", "champion.json");
	private double decisionThreshold = 0.5;

	public Path getModelPath() {
		return modelPath;
	}

	public void setModelPath(Path modelPath) {
		this.modelPath = modelPath;
	}

	public double getDecisionThreshold() {
		return decisionThreshold;
	}

	public void setDecisionThreshold(double decisionThreshold) {
		this.decisionThreshold = decisionThreshold;
	}
}
