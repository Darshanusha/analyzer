package com.stock.analyzer;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import com.stock.analyzer.analysis.application.AnalysisRunRepository;
import com.stock.analyzer.analysis.domain.AnalysisComponentResult;
import com.stock.analyzer.analysis.domain.AnalysisReport;
import com.stock.analyzer.analysis.domain.NarrativeGeneration;
import com.stock.analyzer.analysis.domain.NarrativeOutput;
import com.stock.analyzer.batch.application.BatchAnalysisRepository;
import com.stock.analyzer.forecast.application.LlmForecastRepository;
import com.stock.analyzer.forecast.application.LlmForecastService;
import com.stock.analyzer.forecast.application.LlmInformationGapRepository;
import com.stock.analyzer.forecast.domain.LlmInformationRequest;
import com.stock.analyzer.forecast.domain.LlmInformationRequestAudit;
import com.stock.analyzer.forecast.domain.LlmForecastResult;
import com.stock.analyzer.instrument.application.InstrumentRepository;
import com.stock.analyzer.instrument.domain.InstrumentProfile;
import com.stock.analyzer.news.domain.NewsArticle;
import com.stock.analyzer.persistence.AnalysisDataRepository;
import com.stock.analyzer.prediction.application.PredictionRepository;
import com.stock.analyzer.prediction.application.FeatureEngineeringService;
import com.stock.analyzer.prediction.domain.FeatureSnapshot;
import com.stock.analyzer.prediction.domain.LoadedModelInference;
import com.stock.analyzer.prediction.domain.ModelInference;
import com.stock.analyzer.prediction.domain.ProbabilityModelArtifact;
import com.stock.analyzer.marketdata.domain.DailyPriceBar;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(properties = "management.opentelemetry.enabled=false")
class AnalyzerApplicationIT {

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private AnalysisRunRepository analysisRunRepository;

	@Autowired
	private AnalysisDataRepository analysisDataRepository;

	@Autowired
	private BatchAnalysisRepository batchAnalysisRepository;

	@Autowired
	private PredictionRepository predictionRepository;

	@Autowired
	private LlmForecastRepository llmForecastRepository;

	@Autowired
	private LlmInformationGapRepository llmInformationGapRepository;

	@Test
	void contextLoadsAndRunsFoundationMigration() {
		Integer metadataRows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM analyzer.app_metadata WHERE metadata_key = 'foundation'",
				Integer.class);

		assertThat(metadataRows).isEqualTo(1);

		Integer sharadarProviders = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM analyzer.data_provider WHERE provider_key = 'SHARADAR'",
				Integer.class);

		assertThat(sharadarProviders).isEqualTo(1);

		Integer phaseOneTables = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM information_schema.tables
				WHERE table_schema = 'analyzer'
				  AND table_name IN ('daily_price_bar', 'financial_report', 'news_item',
				                     'analysis_run', 'analysis_component_result', 'batch_analysis')
				""", Integer.class);

		assertThat(phaseOneTables).isEqualTo(6);

		Integer llmForecastTables = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM information_schema.tables
				WHERE table_schema = 'analyzer'
				  AND table_name IN ('llm_forecast', 'llm_forecast_outcome',
				                     'llm_information_gap', 'llm_information_request')
				""", Integer.class);
		assertThat(llmForecastTables).isEqualTo(4);
	}

	@Test
	void persistsAndFindsCanonicalInstrument() {
		InstrumentProfile profile = appleProfile();

		var saved = instrumentRepository.upsert(profile);
		var found = instrumentRepository.findActiveBySymbol("AAPL");

		assertThat(found).contains(saved);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT raw_metadata ->> 'permaticker' FROM analyzer.provider_instrument_mapping WHERE instrument_id = ?",
				String.class, saved.id())).isEqualTo("199059");
	}

	@Test
	void persistsACompleteExplainableAnalysisRun() {
		var instrument = instrumentRepository.upsert(appleProfile());
		var runId = analysisRunRepository.create(instrument, "AAPL");
		analysisRunRepository.markRunning(runId);
		var component = new AnalysisComponentResult("quality.data", "phase1-v1", "GOOD", "Data quality is good",
				Map.of("priceObservationCount", 252), List.of("Fresh EOD data"), List.of("Sharadar"), List.of(), 2L);
		analysisRunRepository.saveComponent(runId, component);
		var narrative = new NarrativeOutput("Evidence summary", List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
		var generation = new NarrativeGeneration(narrative, "DETERMINISTIC_FALLBACK", "OPENAI", "gpt-5-mini",
				"SKIPPED_NOT_ENABLED", null, null, null, 1L, null);
		analysisRunRepository.recordNarrativeInvocation(runId, generation);
		var report = new AnalysisReport(runId, "COMPLETED", "AAPL", "APPLE INC", Instant.now(),
				LocalDate.of(2026, 10, 2), null, "MODEL_NOT_AVAILABLE", null, "GOOD", List.of(component), narrative,
				"DETERMINISTIC_FALLBACK");
		analysisRunRepository.complete(runId, report, report.priceCutoffDate(), "GOOD", generation, 10L);

		var stored = analysisRunRepository.find(runId);

		assertThat(stored).isPresent();
		assertThat(stored.orElseThrow().report()).isEqualTo(report);
		assertThat(analysisDataRepository.loadPeers(instrument.id(), "Technology", null,
				LocalDate.of(2026, 10, 2))).isEmpty();
	}

	@Test
	void persistsNewsAndPartialSuccessBatchState() {
		var instrument = instrumentRepository.upsert(appleProfile());
		var article = new NewsArticle("test-" + UUID.randomUUID(), Instant.parse("2026-10-02T18:00:00Z"), null,
				"Apple reports quarterly results", "Revenue grew", null, "https://example.test/article",
				"Test Author", List.of("AAPL"), List.of("earnings"), List.of("results"), Map.of("test", true));

		assertThat(analysisDataRepository.saveNews(instrument.id(), List.of(article))).isEqualTo(1);
		assertThat(analysisDataRepository.latestNewsUpdate(instrument.id())).contains(article.publishedAt());
		assertThat(analysisDataRepository.loadNews(instrument.id(), article.publishedAt().minusSeconds(1)))
				.extracting(NewsArticle::providerArticleId).contains(article.providerArticleId());

		UUID batchId = batchAnalysisRepository.create(List.of("AAPL", "MSFT"));
		batchAnalysisRepository.markRunning(batchId);
		UUID runId = analysisRunRepository.create(instrument, "AAPL");
		batchAnalysisRepository.linkAnalysis(batchId, "AAPL", runId);
		batchAnalysisRepository.completeItem(batchId, "AAPL");
		batchAnalysisRepository.failItem(batchId, "MSFT", "INSTRUMENT_NOT_FOUND", "Not found");
		batchAnalysisRepository.refreshSummary(batchId, true);

		assertThat(batchAnalysisRepository.find(batchId).orElseThrow().status()).isEqualTo("PARTIAL_SUCCESS");
		assertThat(batchAnalysisRepository.findItems(batchId)).extracting(item -> item.status())
				.containsExactlyInAnyOrder("COMPLETED", "FAILED");
	}

	@Test
	void persistsFeaturePredictionAndResolvesNextObservedSession() {
		var instrument = instrumentRepository.upsert(appleProfile());
		UUID runId = analysisRunRepository.create(instrument, "AAPL");
		LocalDate cutoff = LocalDate.of(2026, 10, 2);
		LocalDate target = LocalDate.of(2026, 10, 5);
		analysisDataRepository.savePrices(instrument.id(), List.of(
				price(cutoff, "100.00"), price(target, "101.50")));
		var snapshot = new FeatureSnapshot(UUID.randomUUID(), runId, instrument.id(), cutoff, target,
				"phase2-v1", "COMPLETE", Map.of("return_1d", 0.01), List.of());
		UUID snapshotId = predictionRepository.saveFeatureSnapshot(snapshot);
		var unavailable = new LoadedModelInference(new ModelInference(null, "MODEL_NOT_AVAILABLE", null, null),
				null, null, null);
		predictionRepository.savePrediction("AAPL", snapshot, snapshotId, unavailable, null, "GOOD");

		assertThat(predictionRepository.resolveOutcomes(instrument.id())).isEqualTo(1);
		var prediction = predictionRepository.findBySymbol("AAPL", 10).stream()
				.filter(value -> value.analysisRunId().equals(runId)).findFirst().orElseThrow();
		assertThat(prediction.probabilityUp()).isNull();
		assertThat(prediction.outcome().actualUp()).isTrue();
		assertThat(prediction.outcome().correct()).isNull();
	}

	@Test
	void registersVersionedChampionAndPersistsCalibratedProbability() {
		var instrument = instrumentRepository.upsert(appleProfile());
		UUID runId = analysisRunRepository.create(instrument, "AAPL");
		LocalDate cutoff = LocalDate.of(2026, 9, 30);
		Map<String, Double> features = FeatureEngineeringService.FEATURE_NAMES.stream()
				.collect(java.util.stream.Collectors.toMap(name -> name, name -> 0.0));
		Map<String, Double> scales = FeatureEngineeringService.FEATURE_NAMES.stream()
				.collect(java.util.stream.Collectors.toMap(name -> name, name -> 1.0));
		var artifact = new ProbabilityModelArtifact(1, "integration-model-v1", "phase2-v1",
				"integration-calibration-v1", "STANDARDIZED_LOGISTIC_REGRESSION_WITH_PLATT_CALIBRATION",
				Instant.parse("2026-09-30T20:00:00Z"), LocalDate.of(2021, 1, 1), cutoff, 5_000,
				FeatureEngineeringService.FEATURE_NAMES, features, scales, features,
				0.0, 1.0, 0.0, 0.5, Map.of("brierScore", 0.24));
		UUID modelId = predictionRepository.registerChampion(artifact,
				java.nio.file.Path.of("model-artifacts", "champion.json"), "a".repeat(64));
		assertThat(predictionRepository.registerChampion(artifact,
				java.nio.file.Path.of("model-artifacts", "champion.json"), "a".repeat(64))).isEqualTo(modelId);
		var snapshot = new FeatureSnapshot(UUID.randomUUID(), runId, instrument.id(), cutoff,
				LocalDate.of(2026, 10, 1), "phase2-v1", "COMPLETE", features, List.of());
		UUID snapshotId = predictionRepository.saveFeatureSnapshot(snapshot);
		var loaded = new LoadedModelInference(new ModelInference(0.61, "AVAILABLE", artifact.modelVersion(),
				artifact.calibrationVersion()), artifact, java.nio.file.Path.of("model-artifacts", "champion.json"),
				"a".repeat(64));

		predictionRepository.savePrediction("AAPL", snapshot, snapshotId, loaded, modelId, "GOOD");

		assertThat(predictionRepository.findBySymbol("AAPL", 20)).anySatisfy(prediction -> {
			assertThat(prediction.analysisRunId()).isEqualTo(runId);
			assertThat(prediction.probabilityUp()).isEqualTo(0.61);
			assertThat(prediction.modelVersion()).isEqualTo("integration-model-v1");
		});
	}

	@Test
	void persistsRanksAndResolvesLlmForecasts() {
		var instrument = instrumentRepository.upsert(appleProfile());
		UUID runId = analysisRunRepository.create(instrument, "AAPL");
		UUID batchId = batchAnalysisRepository.create(List.of("AAPL"));
		batchAnalysisRepository.markRunning(batchId);
		batchAnalysisRepository.linkAnalysis(batchId, "AAPL", runId);
		batchAnalysisRepository.completeItem(batchId, "AAPL");
		batchAnalysisRepository.refreshSummary(batchId, true);
		LocalDate cutoff = LocalDate.of(2026, 10, 2);
		LocalDate target = LocalDate.of(2026, 10, 5);
		analysisDataRepository.savePrices(instrument.id(), List.of(price(cutoff, "100.00"), price(target, "102.00")));
		var forecast = new LlmForecastResult(UUID.randomUUID(), runId, instrument.id(), "AAPL", cutoff, target,
				"AVAILABLE", "UP", 68, "MEDIUM", "Evidence favors an upward close",
				List.of("Positive momentum"), List.of("High valuation"), List.of("Unexpected news"), List.of(),
				LlmForecastService.SCORE_INTERPRETATION, "OPENAI", "test-model", "llm-next-session-v1",
				"b".repeat(64), 100L, 50L, 150L, 25L, null, null, Instant.now(), null);
		llmForecastRepository.save(forecast, Map.of("symbol", "AAPL", "cutoffDate", cutoff.toString()));
		String gapFingerprint = "c".repeat(64);
		llmInformationGapRepository.record(new LlmInformationRequestAudit(UUID.randomUUID(), forecast.forecastId(),
				runId, instrument.id(), "AAPL", cutoff,
				new LlmInformationRequest("OPTIONS", "Thirty-day implied volatility",
						"May affect the next session", "MEDIUM", "OPTIONS_PROVIDER", ""),
				gapFingerprint, "NOT_SEARCHABLE", null, Instant.now()));

		assertThat(llmForecastRepository.findBatchRankings(batchId)).singleElement().satisfies(item -> {
			assertThat(item.rank()).isEqualTo(1);
			assertThat(item.symbol()).isEqualTo("AAPL");
			assertThat(item.upScore()).isEqualTo(68);
		});
		assertThat(llmForecastRepository.resolveOutcomes(instrument.id())).isEqualTo(1);
		assertThat(llmForecastRepository.findBySymbol("AAPL", 10)).anySatisfy(stored -> {
			assertThat(stored.upScore()).isEqualTo(68);
			assertThat(stored.outcome().actualUp()).isTrue();
			assertThat(stored.outcome().correct()).isTrue();
		});
		assertThat(llmInformationGapRepository.find("NEW", 10)).anySatisfy(gap -> {
			assertThat(gap.fingerprint()).isEqualTo(gapFingerprint);
			assertThat(gap.occurrenceCount()).isEqualTo(1);
		});
		assertThat(llmInformationGapRepository.update(gapFingerprint, "PLANNED", "Evaluate provider"))
				.isTrue();
		assertThat(llmInformationGapRepository.find("PLANNED", 10)).singleElement()
				.satisfies(gap -> assertThat(gap.resolutionNotes()).isEqualTo("Evaluate provider"));
	}

	private InstrumentProfile appleProfile() {
		return new InstrumentProfile(
				"SHARADAR", "stocks", "199059", "AAPL", "APPLE INC", "NASDAQ", "USD", true,
				"Domestic Common Stock", "Technology", "Consumer Electronics", "3571", "Manufacturing",
				"Electronic Computers", "Computers", "BBG000B9XRY4", "California; U.S.A", null,
				"https://www.apple.com", LocalDate.of(2014, 9, 24), LocalDate.of(1980, 12, 12),
				null, LocalDate.of(1996, 9, 30), null, LocalDate.of(2026, 10, 2),
				Map.of("table", "stocks", "permaticker", "199059"));
	}

	private DailyPriceBar price(LocalDate date, String close) {
		BigDecimal value = new BigDecimal(close);
		return new DailyPriceBar(date, value, value, value, value, BigDecimal.valueOf(1_000_000),
				value, value, date);
	}
}
