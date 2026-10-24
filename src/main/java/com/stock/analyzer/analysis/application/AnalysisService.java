package com.stock.analyzer.analysis.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.stock.analyzer.analysis.domain.AnalysisComponentResult;
import com.stock.analyzer.analysis.domain.AnalysisReport;
import com.stock.analyzer.analysis.domain.AnalysisRunState;
import com.stock.analyzer.analysis.domain.NarrativeGeneration;
import com.stock.analyzer.api.error.ApiException;
import com.stock.analyzer.application.synchronization.OnDemandDataSynchronizer;
import com.stock.analyzer.application.synchronization.SyncSummary;
import com.stock.analyzer.configuration.AnalysisProperties;
import com.stock.analyzer.fundamental.domain.FinancialReport;
import com.stock.analyzer.forecast.application.LlmForecastService;
import com.stock.analyzer.forecast.domain.LlmForecastResult;
import com.stock.analyzer.instrument.application.InstrumentResolver;
import com.stock.analyzer.instrument.domain.Instrument;
import com.stock.analyzer.marketdata.domain.DailyPriceBar;
import com.stock.analyzer.news.application.NewsProvider;
import com.stock.analyzer.news.domain.NewsArticle;
import com.stock.analyzer.persistence.AnalysisDataRepository;
import com.stock.analyzer.persistence.PeerSnapshot;
import com.stock.analyzer.prediction.application.PredictionService;
import com.stock.analyzer.prediction.domain.PredictionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class AnalysisService {
	private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

	private static final Map<String, String> SECTOR_ETFS = Map.ofEntries(
			Map.entry("technology", "XLK"), Map.entry("financial services", "XLF"),
			Map.entry("healthcare", "XLV"), Map.entry("consumer cyclical", "XLY"),
			Map.entry("consumer defensive", "XLP"), Map.entry("industrials", "XLI"),
			Map.entry("energy", "XLE"), Map.entry("basic materials", "XLB"),
			Map.entry("utilities", "XLU"), Map.entry("real estate", "XLRE"),
			Map.entry("communication services", "XLC"));

	private final InstrumentResolver instrumentResolver;
	private final OnDemandDataSynchronizer synchronizer;
	private final AnalysisDataRepository dataRepository;
	private final AnalysisRunRepository runRepository;
	private final DeterministicAnalysisEngine engine;
	private final EvidenceNarrativeGenerator narrativeGenerator;
	private final NewsProvider newsProvider;
	private final PredictionService predictionService;
	private final LlmForecastService llmForecastService;
	private final AnalysisProperties properties;
	private final ExecutorService executor;
	private final Clock clock;
	private MeterRegistry meterRegistry;

	@Autowired
	void configureMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	@Autowired
	public AnalysisService(InstrumentResolver instrumentResolver, OnDemandDataSynchronizer synchronizer,
			AnalysisDataRepository dataRepository, AnalysisRunRepository runRepository,
			DeterministicAnalysisEngine engine, EvidenceNarrativeGenerator narrativeGenerator,
			NewsProvider newsProvider, PredictionService predictionService, LlmForecastService llmForecastService,
			AnalysisProperties properties, ExecutorService executor) {
		this(instrumentResolver, synchronizer, dataRepository, runRepository, engine, narrativeGenerator,
				newsProvider, predictionService, llmForecastService, properties, executor, Clock.systemUTC());
	}

	AnalysisService(InstrumentResolver instrumentResolver, OnDemandDataSynchronizer synchronizer,
			AnalysisDataRepository dataRepository, AnalysisRunRepository runRepository,
			DeterministicAnalysisEngine engine, EvidenceNarrativeGenerator narrativeGenerator,
			NewsProvider newsProvider, PredictionService predictionService, LlmForecastService llmForecastService,
			AnalysisProperties properties, ExecutorService executor, Clock clock) {
		this.instrumentResolver = instrumentResolver;
		this.synchronizer = synchronizer;
		this.dataRepository = dataRepository;
		this.runRepository = runRepository;
		this.engine = engine;
		this.narrativeGenerator = narrativeGenerator;
		this.newsProvider = newsProvider;
		this.predictionService = predictionService;
		this.llmForecastService = llmForecastService;
		this.properties = properties;
		this.executor = executor;
		this.clock = clock;
	}

	public AnalysisSubmission submit(String symbol) {
		return submit(symbol, false, true);
	}

	public AnalysisSubmission submit(String symbol, boolean forceReanalysis) {
		return submit(symbol, forceReanalysis, true);
	}

	public AnalysisSubmission submit(String symbol, boolean forceReanalysis, boolean filter) {
		Instrument instrument = instrumentResolver.resolve(symbol, false);
		if (!forceReanalysis) {
			LocalDate today = LocalDate.now(clock);
			Instant dayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant();
			Instant nextDayStart = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
			var existing = runRepository.findCompletedForDay(instrument.id(), dayStart, nextDayStart);
			if (existing.isPresent() && reusable(existing.orElseThrow().report(), filter)) {
				AnalysisRunState state = existing.orElseThrow();
				log.info("Reusing today's completed analysis: symbol={}, analysisRunId={}, completedAt={}",
						instrument.symbol(), state.analysisRunId(), state.completedAt());
				return new AnalysisSubmission(state.analysisRunId(),
						CompletableFuture.completedFuture(state.report()), true);
			}
		}
		UUID runId = runRepository.create(instrument, instrument.symbol());
		CompletableFuture<AnalysisReport> completion = CompletableFuture.supplyAsync(
				() -> execute(runId, instrument, filter), executor);
		return new AnalysisSubmission(runId, completion, false);
	}

	public AnalysisRunState get(UUID analysisRunId) {
		return runRepository.find(analysisRunId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ANALYSIS_NOT_FOUND",
						"No analysis run was found for " + analysisRunId));
	}

	public List<AnalysisRunState> history(String symbol, int limit) {
		String normalized = symbol == null ? "" : symbol.strip().toUpperCase(Locale.ROOT);
		return runRepository.findBySymbol(normalized, Math.max(1, Math.min(limit, 100)));
	}

	private AnalysisReport execute(UUID runId, Instrument instrument, boolean filter) {
		long started = System.nanoTime();
		runRepository.markRunning(runId);
		try {
			long stepStarted = System.nanoTime();
			SyncSummary sync = synchronizer.synchronize(instrument);
			recordStep("synchronization", stepStarted);
			LocalDate priceCutoff = sync.priceCutoff();
			if (priceCutoff == null) {
				throw new IllegalStateException("No completed EOD price is available for " + instrument.symbol());
			}

			stepStarted = System.nanoTime();
			List<DailyPriceBar> prices = dataRepository.loadPrices(instrument.id(), priceCutoff, 600);
			List<FinancialReport> reports = dataRepository.loadFinancialReports(instrument.id(), priceCutoff);
			List<NewsArticle> news = dataRepository.loadNews(instrument.id(),
					Instant.now(clock).minusSeconds(properties.getNewsLookbackDays() * 86_400L));
			FinancialReport latestReport = latestAnnualReport(reports);
			List<PeerSnapshot> peers = dataRepository.loadPeers(instrument.id(), instrument.sector(),
					instrument.industry(), priceCutoff);

			String sectorEtf = sectorEtf(instrument.sector());
			List<DailyPriceBar> marketPrices = benchmarkPrices("SPY", priceCutoff);
			List<DailyPriceBar> sectorPrices = "SPY".equals(sectorEtf)
					? marketPrices : benchmarkPrices(sectorEtf, priceCutoff);
			recordStep("data_loading", stepStarted);

			stepStarted = System.nanoTime();
			List<AnalysisComponentResult> components = new ArrayList<>();
			components.add(engine.fundamental(reports));
			components.add(engine.peerAndSector(instrument, latestReport, peers));
			components.add(engine.technical(prices));
			components.add(engine.market(prices, marketPrices, sectorPrices, sectorEtf));
			components.add(engine.news(news, newsProvider.isConfigured()));
			AnalysisComponentResult quality = engine.quality(sync, prices, reports, newsProvider.isConfigured());
			components.add(quality);
			recordStep("deterministic_analysis", stepStarted);
			stepStarted = System.nanoTime();
			PredictionResult prediction = predictionService.predict(runId, instrument, priceCutoff,
					prices, marketPrices, quality.status());
			components.add(predictionComponent(prediction));
			recordStep("baseline_prediction", stepStarted);
			stepStarted = System.nanoTime();
			LlmForecastResult llmForecast = llmForecastService.forecast(runId, instrument, priceCutoff,
					quality.status(), components, filter);
			List<LlmForecastResult> llmForecasts = llmForecastService.forAnalysisRun(runId);
			if (llmForecasts == null || llmForecasts.isEmpty()) llmForecasts = List.of(llmForecast);
			llmForecasts.forEach(forecast -> components.add(llmForecastComponent(forecast)));
			recordStep("llm_forecast", stepStarted);
			stepStarted = System.nanoTime();
			components.forEach(component -> runRepository.saveComponent(runId, component));
			recordStep("component_persistence", stepStarted);

			stepStarted = System.nanoTime();
			NarrativeGeneration narrative = narrativeGenerator.generate(instrument.symbol(), components);
			runRepository.recordNarrativeInvocation(runId, narrative);
			recordStep("narrative", stepStarted);
			AnalysisReport report = new AnalysisReport(runId, "COMPLETED", instrument.symbol(), instrument.name(),
					Instant.now(clock), priceCutoff, prediction.probabilityUp(), prediction.status(), llmForecast, llmForecasts,
					quality.status(),
					List.copyOf(components), narrative.output(), narrative.source());
			runRepository.complete(runId, report, priceCutoff, quality.status(), narrative, elapsedMillis(started));
			recordStep("total", started);
			return report;
		}
		catch (RuntimeException exception) {
			runRepository.fail(runId, exception.getClass().getSimpleName(), safeMessage(exception), elapsedMillis(started));
			throw new CompletionException(exception);
		}
	}

	private boolean reusable(AnalysisReport report, boolean filter) {
		if (report == null || report.llmForecast() == null) return false;
		return filter || !"FILTERED_OUT".equals(report.llmForecast().status());
	}

	private List<DailyPriceBar> benchmarkPrices(String symbol, LocalDate cutoff) {
		try {
			Instrument benchmark = instrumentResolver.resolve(symbol, false);
			synchronizer.synchronizePricesOnly(benchmark);
			return dataRepository.loadPrices(benchmark.id(), cutoff, 600);
		}
		catch (RuntimeException ignored) {
			return List.of();
		}
	}

	private FinancialReport latestAnnualReport(List<FinancialReport> reports) {
		return reports.stream().filter(report -> "ART".equals(report.dimension()) || "ARY".equals(report.dimension()))
				.max(Comparator.comparing(FinancialReport::providerDate)).orElse(null);
	}

	private String sectorEtf(String sector) {
		return sector == null ? "SPY" : SECTOR_ETFS.getOrDefault(sector.toLowerCase(Locale.ROOT), "SPY");
	}

	private AnalysisComponentResult predictionComponent(PredictionResult prediction) {
		Map<String, Object> metrics = new java.util.LinkedHashMap<>();
		metrics.put("targetSession", prediction.targetSession());
		metrics.put("featureSetVersion", prediction.featureSetVersion());
		if (prediction.probabilityUp() != null) metrics.put("probabilityUp", prediction.probabilityUp());
		if (prediction.modelVersion() != null) metrics.put("modelVersion", prediction.modelVersion());
		if (prediction.calibrationVersion() != null) metrics.put("calibrationVersion", prediction.calibrationVersion());
		String summary = prediction.probabilityUp() == null
				? "No promoted compatible probability model is available"
				: "Calibrated next-session probability generated by the promoted baseline model";
		return new AnalysisComponentResult("prediction.next-session", "phase2-v1", prediction.status(), summary,
				Map.copyOf(metrics), List.of(), List.of(), prediction.probabilityUp() == null
				? List.of("A probability is withheld until a compatible champion model is promoted") : List.of(), 0L);
	}

	private AnalysisComponentResult llmForecastComponent(LlmForecastResult forecast) {
		Map<String, Object> metrics = new java.util.LinkedHashMap<>();
		metrics.put("targetSession", forecast.targetSession());
		metrics.put("scoreInterpretation", forecast.scoreInterpretation());
		metrics.put("model", forecast.model());
		metrics.put("promptVersion", forecast.promptVersion());
		if (forecast.direction() != null) metrics.put("direction", forecast.direction());
		if (forecast.upScore() != null) metrics.put("upScore", forecast.upScore());
		if (forecast.confidence() != null) metrics.put("confidence", forecast.confidence());
		List<String> limitations = new ArrayList<>(forecast.dataLimitations());
		if (!"AVAILABLE".equals(forecast.status()) && forecast.errorMessage() != null) {
			limitations.add(forecast.errorMessage());
		}
		return new AnalysisComponentResult("prediction.llm-" + forecast.horizonTradingSessions() + "-session",
				forecast.promptVersion(),
				forecast.status(), forecast.thesis(), Map.copyOf(metrics), List.of(), List.of(),
				limitations.stream().distinct().toList(), forecast.durationMs());
	}

	private long elapsedMillis(long started) {
		return (System.nanoTime() - started) / 1_000_000L;
	}

	private void recordStep(String step, long started) {
		if (meterRegistry != null) meterRegistry.timer("stock.analysis.step.duration", "step", step)
				.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
	}

	private String safeMessage(RuntimeException exception) {
		String message = exception.getMessage();
		if (exception instanceof CompletionException && exception.getCause() != null) {
			message = exception.getCause().getMessage();
		}
		if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
		return message.substring(0, Math.min(1_000, message.length()));
	}
}
