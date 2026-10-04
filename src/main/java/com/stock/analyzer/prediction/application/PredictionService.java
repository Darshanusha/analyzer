package com.stock.analyzer.prediction.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.stock.analyzer.instrument.domain.Instrument;
import com.stock.analyzer.marketdata.domain.DailyPriceBar;
import com.stock.analyzer.prediction.domain.FeatureSnapshot;
import com.stock.analyzer.prediction.domain.LoadedModelInference;
import com.stock.analyzer.prediction.domain.PredictionResult;
import org.springframework.stereotype.Service;

@Service
public class PredictionService {

	private final FeatureEngineeringService featureEngineering;
	private final ProbabilityModelRegistry modelRegistry;
	private final PredictionRepository repository;

	public PredictionService(FeatureEngineeringService featureEngineering,
			ProbabilityModelRegistry modelRegistry, PredictionRepository repository) {
		this.featureEngineering = featureEngineering;
		this.modelRegistry = modelRegistry;
		this.repository = repository;
	}

	public PredictionResult predict(UUID analysisRunId, Instrument instrument, LocalDate cutoff,
			List<DailyPriceBar> stockPrices, List<DailyPriceBar> marketPrices, String dataQualityStatus) {
		repository.resolveOutcomes(instrument.id());
		FeatureSnapshot snapshot = featureEngineering.create(analysisRunId, instrument, cutoff, stockPrices, marketPrices);
		UUID snapshotId = repository.saveFeatureSnapshot(snapshot);
		LoadedModelInference inference = modelRegistry.predict(snapshot);
		UUID modelDefinitionId = inference.artifact() == null ? null
				: repository.registerChampion(inference.artifact(), inference.artifactPath(), inference.artifactSha256());
		return repository.savePrediction(instrument.symbol(), snapshot, snapshotId, inference,
				modelDefinitionId, dataQualityStatus);
	}

	public List<PredictionResult> history(String symbol, int limit) {
		String normalized = symbol == null ? "" : symbol.strip().toUpperCase(Locale.ROOT);
		return repository.findBySymbol(normalized, Math.max(1, Math.min(limit, 100)));
	}
}
