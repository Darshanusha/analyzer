package com.stock.analyzer.prediction.application;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.stock.analyzer.prediction.domain.FeatureSnapshot;
import com.stock.analyzer.prediction.domain.LoadedModelInference;
import com.stock.analyzer.prediction.domain.PredictionResult;
import com.stock.analyzer.prediction.domain.PredictionPerformanceSummary;
import com.stock.analyzer.prediction.domain.ProbabilityModelArtifact;

public interface PredictionRepository {

	UUID saveFeatureSnapshot(FeatureSnapshot snapshot);

	UUID registerChampion(ProbabilityModelArtifact artifact, Path artifactPath, String sha256);

	PredictionResult savePrediction(String symbol, FeatureSnapshot snapshot, UUID featureSnapshotId,
			LoadedModelInference inference, UUID modelDefinitionId, String dataQualityStatus);

	List<PredictionResult> findBySymbol(String symbol, int limit);

	int resolveOutcomes(UUID instrumentId);

	PredictionPerformanceSummary performanceSummary();
}
