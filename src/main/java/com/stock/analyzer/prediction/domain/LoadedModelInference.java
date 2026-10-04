package com.stock.analyzer.prediction.domain;

import java.nio.file.Path;

public record LoadedModelInference(
		ModelInference inference,
		ProbabilityModelArtifact artifact,
		Path artifactPath,
		String artifactSha256) {
}
