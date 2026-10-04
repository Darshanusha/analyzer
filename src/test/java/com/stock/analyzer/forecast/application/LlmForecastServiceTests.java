package com.stock.analyzer.forecast.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stock.analyzer.analysis.domain.AnalysisComponentResult;
import com.stock.analyzer.configuration.LlmForecastProperties;
import com.stock.analyzer.forecast.domain.LlmForecastCall;
import com.stock.analyzer.forecast.domain.LlmForecastOutput;
import com.stock.analyzer.forecast.domain.LlmForecastResult;
import com.stock.analyzer.forecast.domain.LlmInformationRequest;
import com.stock.analyzer.forecast.domain.LlmInformationRequestAudit;
import com.stock.analyzer.forecast.domain.LlmWebResearchResult;
import com.stock.analyzer.forecast.domain.LlmWebSource;
import com.stock.analyzer.instrument.domain.Instrument;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmForecastServiceTests {

	private final LlmForecastClient client = mock(LlmForecastClient.class);
	private final LlmWebResearchClient webResearchClient = mock(LlmWebResearchClient.class);
	private final LlmForecastRepository repository = mock(LlmForecastRepository.class);
	private final LlmInformationGapRepository gapRepository = mock(LlmInformationGapRepository.class);
	private final LlmForecastProperties properties = properties(true);
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-04T09:00:00Z"), ZoneOffset.UTC);
	private final LlmForecastService service = new LlmForecastService(client, webResearchClient, repository,
			gapRepository, properties, new ObjectMapper(), new SimpleMeterRegistry(), clock);

	@Test
	@SuppressWarnings("unchecked")
	void createsComparableForecastFromStructuredOutput() {
		when(client.isAvailable()).thenReturn(true);
		when(client.configuredModel()).thenReturn("test-model");
		when(client.forecast(any())).thenReturn(new LlmForecastCall(
				new LlmForecastOutput(72, "MEDIUM", "Evidence modestly favors an upward close",
						List.of("Positive momentum"), List.of("High valuation"),
						List.of("Unexpected news"), List.of("News coverage is unavailable")),
				"test-model-snapshot", 100L, 50L, 150L));

		LlmForecastResult result = service.forecast(UUID.randomUUID(), instrument(),
				LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5), "LIMITED", components());

		assertThat(result.status()).isEqualTo("AVAILABLE");
		assertThat(result.direction()).isEqualTo("UP");
		assertThat(result.upScore()).isEqualTo(72);
		assertThat(result.scoreInterpretation()).contains("NOT_A_PROBABILITY");
		assertThat(result.model()).isEqualTo("test-model-snapshot");
		verify(repository).resolveOutcomes(instrument().id());
		ArgumentCaptor<Map<String, Object>> snapshot = ArgumentCaptor.forClass(Map.class);
		verify(repository).save(any(LlmForecastResult.class), snapshot.capture());
		assertThat(snapshot.getValue()).containsEntry("symbol", "AAPL");
		assertThat((List<?>) snapshot.getValue().get("components")).hasSize(1);
	}

	@Test
	void recordsDisabledForecastWithoutCallingOpenAi() {
		LlmForecastProperties disabled = properties(false);
		LlmForecastService disabledService = new LlmForecastService(client, webResearchClient, repository,
				gapRepository, disabled, new ObjectMapper(), new SimpleMeterRegistry(), clock);

		LlmForecastResult result = disabledService.forecast(UUID.randomUUID(), instrument(),
				LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5), "GOOD", components());

		assertThat(result.status()).isEqualTo("DISABLED");
		assertThat(result.upScore()).isNull();
		verify(client, never()).forecast(any());
		verify(repository).save(any(LlmForecastResult.class), any());
	}

	@Test
	void convertsInvalidModelScoreIntoAuditableFailure() {
		when(client.isAvailable()).thenReturn(true);
		when(client.configuredModel()).thenReturn("test-model");
		when(client.forecast(any())).thenReturn(new LlmForecastCall(
				new LlmForecastOutput(130, "HIGH", "Invalid score", List.of(), List.of(), List.of(), List.of()),
				"test-model", 10L, 10L, 20L));

		LlmForecastResult result = service.forecast(UUID.randomUUID(), instrument(),
				LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5), "GOOD", components());

		assertThat(result.status()).isEqualTo("FAILED");
		assertThat(result.errorCode()).isEqualTo("IllegalArgumentException");
		assertThat(result.errorMessage()).contains("between 0 and 100");
	}

	@Test
	void recordsUnsupportedInformationRequestAsAnAuditableGap() {
		when(client.isAvailable()).thenReturn(true);
		when(client.configuredModel()).thenReturn("test-model");
		when(webResearchClient.isAvailable()).thenReturn(false);
		when(client.forecast(any())).thenReturn(new LlmForecastCall(
				new LlmForecastOutput(52, "LOW", "Evidence is balanced", List.of(), List.of(), List.of(),
						List.of(), List.of(new LlmInformationRequest("OPTIONS", "Thirty-day implied volatility",
								"Near-term options positioning may affect the next session", "MEDIUM",
								"OPTIONS_PROVIDER", "AAPL 30 day implied volatility"))),
				"test-model", 10L, 10L, 20L));

		LlmForecastResult result = service.forecast(UUID.randomUUID(), instrument(),
				LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5), "GOOD", components());

		assertThat(result.status()).isEqualTo("AVAILABLE");
		assertThat(result.dataLimitations()).anyMatch(value -> value.contains("Thirty-day implied volatility"));
		ArgumentCaptor<LlmInformationRequestAudit> audit =
				ArgumentCaptor.forClass(LlmInformationRequestAudit.class);
		verify(gapRepository).record(audit.capture());
		assertThat(audit.getValue().resolutionStatus()).isEqualTo("WEB_NOT_ALLOWED");
		assertThat(audit.getValue().fingerprint()).hasSize(64);
	}

	@Test
	@SuppressWarnings("unchecked")
	void performsOneBoundedResearchRoundAndSendsResultsBackToTheModel() {
		when(client.isAvailable()).thenReturn(true);
		when(client.configuredModel()).thenReturn("test-model");
		when(webResearchClient.isAvailable()).thenReturn(true);
		LlmForecastOutput initial = new LlmForecastOutput(50, "LOW", "A filing detail is missing",
				List.of(), List.of(), List.of(), List.of(), List.of(new LlmInformationRequest(
						"REGULATORY", "Latest material SEC filing", "It may contain a new catalyst", "HIGH",
						"SEC", "AAPL latest material filing site:sec.gov")));
		LlmForecastOutput enriched = new LlmForecastOutput(61, "MEDIUM", "The filing supports the thesis",
				List.of("Official filing is supportive"), List.of(), List.of(), List.of());
		when(client.forecast(any())).thenReturn(new LlmForecastCall(initial, "test-model", 10L, 5L, 15L),
				new LlmForecastCall(enriched, "test-model", 20L, 10L, 30L));
		when(webResearchClient.research(any(), any(), any())).thenReturn(new LlmWebResearchResult(
				"AVAILABLE", "A cited filing summary", List.of(new LlmWebSource(
				"https://www.sec.gov/example", "SEC filing", "www.sec.gov")), "search-model",
				5L, 5L, 10L, 25L, null, null));

		LlmForecastResult result = service.forecast(UUID.randomUUID(), instrument(),
				LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 5), "GOOD", components());

		assertThat(result.upScore()).isEqualTo(61);
		assertThat(result.inputTokens()).isEqualTo(30L);
		verify(client, times(2)).forecast(any());
		ArgumentCaptor<Map<String, Object>> snapshot = ArgumentCaptor.forClass(Map.class);
		verify(repository).save(any(LlmForecastResult.class), snapshot.capture());
		assertThat((List<?>) snapshot.getValue().get("webResearch")).hasSize(1);
		ArgumentCaptor<LlmInformationRequestAudit> audit =
				ArgumentCaptor.forClass(LlmInformationRequestAudit.class);
		verify(gapRepository).record(audit.capture());
		assertThat(audit.getValue().resolutionStatus()).isEqualTo("WEB_RESOLVED");
	}

	private LlmForecastProperties properties(boolean enabled) {
		LlmForecastProperties value = new LlmForecastProperties();
		value.setEnabled(enabled);
		return value;
	}

	private Instrument instrument() {
		return new Instrument(UUID.fromString("11111111-1111-1111-1111-111111111111"), "AAPL", "APPLE INC",
				"NASDAQ", "USD", true, "Technology", "Consumer Electronics", "199059", "stocks",
				LocalDate.of(2026, 10, 2));
	}

	private List<AnalysisComponentResult> components() {
		return List.of(
				new AnalysisComponentResult("technical.daily", "phase1-v1", "AVAILABLE", "Technical evidence",
						Map.of("rsi14", 50.0), List.of("RSI is neutral"), List.of(), List.of(), 1L),
				new AnalysisComponentResult("prediction.next-session", "phase2-v1", "MODEL_NOT_AVAILABLE",
						"No model", Map.of(), List.of(), List.of(), List.of(), 0L));
	}
}
