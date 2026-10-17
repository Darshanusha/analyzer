package com.stock.analyzer.analysis.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import com.stock.analyzer.analysis.domain.AnalysisReport;
import com.stock.analyzer.analysis.domain.AnalysisRunState;
import com.stock.analyzer.application.synchronization.OnDemandDataSynchronizer;
import com.stock.analyzer.configuration.AnalysisProperties;
import com.stock.analyzer.forecast.application.LlmForecastService;
import com.stock.analyzer.forecast.domain.LlmForecastResult;
import com.stock.analyzer.instrument.application.InstrumentResolver;
import com.stock.analyzer.instrument.domain.Instrument;
import com.stock.analyzer.news.application.NewsProvider;
import com.stock.analyzer.persistence.AnalysisDataRepository;
import com.stock.analyzer.prediction.application.PredictionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalysisServiceTests {

	private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
	private final InstrumentResolver instrumentResolver = mock(InstrumentResolver.class);
	private final OnDemandDataSynchronizer synchronizer = mock(OnDemandDataSynchronizer.class);
	private final AnalysisDataRepository dataRepository = mock(AnalysisDataRepository.class);
	private final AnalysisRunRepository runRepository = mock(AnalysisRunRepository.class);
	private final DeterministicAnalysisEngine engine = mock(DeterministicAnalysisEngine.class);
	private final EvidenceNarrativeGenerator narrativeGenerator = mock(EvidenceNarrativeGenerator.class);
	private final NewsProvider newsProvider = mock(NewsProvider.class);
	private final PredictionService predictionService = mock(PredictionService.class);
	private final LlmForecastService llmForecastService = mock(LlmForecastService.class);
	private final AnalysisProperties properties = mock(AnalysisProperties.class);
	private final ExecutorService executor = mock(ExecutorService.class);
	private final AnalysisService service = new AnalysisService(instrumentResolver, synchronizer, dataRepository,
			runRepository, engine, narrativeGenerator, newsProvider, predictionService, llmForecastService,
			properties, executor, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void reusesTodaysCompletedAnalysisByDefault() {
		Instrument instrument = instrument();
		AnalysisReport report = report();
		AnalysisRunState state = new AnalysisRunState(report.analysisRunId(), "COMPLETED", "AAPL",
				NOW.minusSeconds(60), NOW.minusSeconds(50), NOW.minusSeconds(10), report, null, null);
		Instant dayStart = Instant.parse("2026-10-04T00:00:00Z");
		Instant nextDayStart = Instant.parse("2026-10-05T00:00:00Z");
		when(instrumentResolver.resolve("AAPL", false)).thenReturn(instrument);
		when(runRepository.findCompletedForDay(instrument.id(), dayStart, nextDayStart))
				.thenReturn(Optional.of(state));

		AnalysisSubmission submission = service.submit("AAPL");

		assertThat(submission.reusedExistingAnalysis()).isTrue();
		assertThat(submission.analysisRunId()).isEqualTo(report.analysisRunId());
		assertThat(submission.completion()).isCompletedWithValue(report);
		verify(runRepository, never()).create(any(), any());
	}

	@Test
	void forceReanalysisCreatesANewRun() {
		Instrument instrument = instrument();
		UUID newRunId = UUID.randomUUID();
		when(instrumentResolver.resolve("AAPL", false)).thenReturn(instrument);
		when(runRepository.create(instrument, "AAPL")).thenReturn(newRunId);

		AnalysisSubmission submission = service.submit("AAPL", true);

		assertThat(submission.reusedExistingAnalysis()).isFalse();
		assertThat(submission.analysisRunId()).isEqualTo(newRunId);
		verify(runRepository, never()).findCompletedForDay(any(), any(), any());
		verify(executor).execute(any(Runnable.class));
	}

	private Instrument instrument() {
		return new Instrument(UUID.randomUUID(), "AAPL", "APPLE INC", "NASDAQ", "USD", true,
				"Technology", "Consumer Electronics", "199059", "stocks", LocalDate.of(2026, 10, 2));
	}

	private AnalysisReport report() {
		LlmForecastResult forecast = new LlmForecastResult(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), "AAPL", LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5),
				"AVAILABLE", "UNCERTAIN", 52, "LOW", "Balanced", List.of(), List.of(), List.of(),
				List.of(), "TEST", "OPENAI", "test-model", "test-prompt", "hash", 1L, 1L, 2L,
				1L, null, null, NOW, null);
		return new AnalysisReport(UUID.randomUUID(), "COMPLETED", "AAPL", "APPLE INC", NOW,
				LocalDate.of(2026, 10, 2), null, "MODEL_NOT_AVAILABLE", forecast, "LIMITED",
				List.of(), null, "DETERMINISTIC_FALLBACK");
	}
}
