package com.stock.analyzer.forecast.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.stock.analyzer.analysis.domain.AnalysisComponentResult;
import com.stock.analyzer.api.error.ApiException;
import com.stock.analyzer.configuration.LlmForecastProperties;
import com.stock.analyzer.forecast.domain.BatchLlmRankingItem;
import com.stock.analyzer.forecast.domain.BatchLlmRankingResponse;
import com.stock.analyzer.forecast.domain.LlmForecastCall;
import com.stock.analyzer.forecast.domain.LlmCandidateFilterDecision;
import com.stock.analyzer.forecast.domain.LlmInformationGapSummary;
import com.stock.analyzer.forecast.domain.LlmInformationRequest;
import com.stock.analyzer.forecast.domain.LlmInformationRequestAudit;
import com.stock.analyzer.forecast.domain.LlmForecastOutput;
import com.stock.analyzer.forecast.domain.LlmForecastPerformanceSummary;
import com.stock.analyzer.forecast.domain.LlmForecastResult;
import com.stock.analyzer.forecast.domain.LlmWebResearchResult;
import com.stock.analyzer.instrument.domain.Instrument;
import com.stock.analyzer.prediction.application.UsMarketCalendar;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class LlmForecastService {

	public static final String SCORE_INTERPRETATION =
			"UNCALIBRATED_LLM_UP_SCORE_0_TO_100_NOT_A_PROBABILITY_OR_TRADE_RECOMMENDATION";
	private static final Logger log = LoggerFactory.getLogger(LlmForecastService.class);
	private static final Set<String> CONFIDENCE_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");
	private static final Set<String> GAP_WORKFLOW_STATUSES = Set.of(
			"NEW", "UNDER_REVIEW", "PLANNED", "AVAILABLE", "REJECTED");
	private static final Set<String> IMPORTANCE_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");
	private static final Pattern SENSITIVE_VALUE = Pattern.compile(
			"(?i)(bearer|api[_-]?key|token)(\\s*[=:]\\s*|\\s+)([^&\\s,\\\"}]+)");

	private final LlmForecastClient client;
	private final LlmCandidateFilter candidateFilter;
	private final LlmWebResearchClient webResearchClient;
	private final LlmForecastRepository repository;
	private final LlmInformationGapRepository gapRepository;
	private final LlmForecastProperties properties;
	private final ObjectMapper objectMapper;
	private final MeterRegistry meterRegistry;
	private final Clock clock;

	@Autowired
	public LlmForecastService(LlmForecastClient client, LlmWebResearchClient webResearchClient,
			LlmForecastRepository repository, LlmInformationGapRepository gapRepository,
			LlmForecastProperties properties, LlmCandidateFilter candidateFilter,
			ObjectMapper objectMapper, MeterRegistry meterRegistry) {
		this(client, webResearchClient, repository, gapRepository, properties, candidateFilter, objectMapper, meterRegistry,
				Clock.systemUTC());
	}

	LlmForecastService(LlmForecastClient client, LlmWebResearchClient webResearchClient,
			LlmForecastRepository repository, LlmInformationGapRepository gapRepository,
			LlmForecastProperties properties, ObjectMapper objectMapper, MeterRegistry meterRegistry, Clock clock) {
		this(client, webResearchClient, repository, gapRepository, properties, new LlmCandidateFilter(properties),
				objectMapper, meterRegistry, clock);
	}

	LlmForecastService(LlmForecastClient client, LlmWebResearchClient webResearchClient,
			LlmForecastRepository repository, LlmInformationGapRepository gapRepository,
			LlmForecastProperties properties, LlmCandidateFilter candidateFilter,
			ObjectMapper objectMapper, MeterRegistry meterRegistry, Clock clock) {
		this.client = client;
		this.candidateFilter = candidateFilter;
		this.webResearchClient = webResearchClient;
		this.repository = repository;
		this.gapRepository = gapRepository;
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.meterRegistry = meterRegistry;
		this.clock = clock;
		validateConfiguration();
	}

	public LlmForecastResult forecast(UUID analysisRunId, Instrument instrument, LocalDate cutoffDate,
			LocalDate targetSession, String dataQualityStatus, List<AnalysisComponentResult> components) {
		return forecast(analysisRunId, instrument, cutoffDate, targetSession, dataQualityStatus, components, false);
	}

	public LlmForecastResult forecast(UUID analysisRunId, Instrument instrument, LocalDate cutoffDate,
			LocalDate targetSession, String dataQualityStatus, List<AnalysisComponentResult> components,
			boolean filterEnabled) {
		long started = System.nanoTime();
		UUID forecastId = UUID.randomUUID();
		Instant informationCutoff = Instant.now(clock);
		repository.resolveOutcomes(instrument.id());
		Map<String, Object> snapshot = inputSnapshot(instrument, cutoffDate, targetSession,
				dataQualityStatus, components, informationCutoff);
		LlmCandidateFilterDecision filterDecision = filterEnabled ? candidateFilter.evaluate(components) : null;
		meterRegistry.counter("stock.analysis.llm.filter", "enabled", Boolean.toString(filterEnabled),
				"outcome", filterDecision == null ? "bypassed" : (filterDecision.eligible() ? "passed" : "filtered"))
				.increment();
		if (filterDecision != null) {
			Map<String, Object> filteredSnapshot = new LinkedHashMap<>(snapshot);
			filteredSnapshot.put("javaCandidateFilter", filterSnapshot(filterDecision));
			snapshot = Collections.unmodifiableMap(filteredSnapshot);
		}
		String inputJson = json(snapshot);
		String inputSha256 = sha256(inputJson);

		if (!properties.isEnabled()) {
			return save(skipped(forecastId, analysisRunId, instrument, cutoffDate, targetSession, "DISABLED",
					"LLM_FORECAST_DISABLED", "Set LLM_FORECAST_ENABLED=true to enable OpenAI forecasting",
					inputSha256, started), snapshot, started);
		}
		if (!client.isAvailable()) {
			return save(skipped(forecastId, analysisRunId, instrument, cutoffDate, targetSession, "NOT_CONFIGURED",
					"OPENAI_NOT_CONFIGURED", "Configure OPENAI_CHAT_PROVIDER=openai and OPENAI_API_KEY",
					inputSha256, started), snapshot, started);
		}
		if ("INSUFFICIENT".equals(dataQualityStatus)) {
			return save(skipped(forecastId, analysisRunId, instrument, cutoffDate, targetSession, "ABSTAINED",
					"INSUFFICIENT_DATA", "The deterministic data-quality check is insufficient",
					inputSha256, started), snapshot, started);
		}
		if (filterDecision != null && !filterDecision.eligible()) {
			String message = "Java candidate filter excluded the stock from the LLM call: "
					+ String.join("; ", filterDecision.reasons());
			log.info("LLM forecast filtered out before OpenAI call: analysisRunId={}, symbol={}, method={}, positiveSignals={}, technicalSignals={}",
					analysisRunId, instrument.symbol(), filterDecision.method(), filterDecision.positiveSignalCount(),
					filterDecision.technicalSignalCount());
			return save(skipped(forecastId, analysisRunId, instrument, cutoffDate, targetSession, "FILTERED_OUT",
					"JAVA_CANDIDATE_FILTER", message, inputSha256, started), snapshot, started);
		}

		try {
			LlmForecastCall firstCall = client.forecast(inputJson);
			repository.saveInteractions(analysisRunId, firstCall.interactions());
			List<LlmInformationRequestAudit> audits = new ArrayList<>();
			List<Map<String, Object>> webEvidence = researchRequests(forecastId, analysisRunId, instrument,
					cutoffDate, informationCutoff, firstCall.output().informationRequests(), audits);
			LlmForecastCall call = firstCall;
			if (!webEvidence.isEmpty()) {
				Map<String, Object> enriched = new LinkedHashMap<>(snapshot);
				enriched.put("webResearch", webEvidence);
				enriched.put("researchRound", 1);
				snapshot = Collections.unmodifiableMap(enriched);
				call = combine(firstCall, client.forecast(json(snapshot)));
				recordLimitReached(forecastId, analysisRunId, instrument, cutoffDate,
						call.output().informationRequests(), audits);
			}
			LlmForecastOutput output = validate(call.output());
			output = addUnresolvedLimitations(output, audits);
			String direction = direction(output.upScore());
			LlmForecastResult result = new LlmForecastResult(forecastId, analysisRunId, instrument.id(),
					instrument.symbol(), cutoffDate, targetSession, "AVAILABLE", direction, output.upScore(),
					output.confidence().toUpperCase(Locale.ROOT), clean(output.thesis(), 1_000),
					clean(output.bullishEvidence()), clean(output.bearishEvidence()), clean(output.risks()),
					clean(output.dataLimitations()), SCORE_INTERPRETATION, "OPENAI", call.model(),
					properties.getPromptVersion(), inputSha256, call.inputTokens(), call.outputTokens(), call.totalTokens(),
					elapsedMillis(started), null, null, Instant.now(clock), null, 1, informationCutoff);
			log.info("LLM forecast completed: analysisRunId={}, symbol={}, direction={}, upScore={}, confidence={}, model={}",
					analysisRunId, instrument.symbol(), direction, output.upScore(), output.confidence(), call.model());
			LlmForecastResult saved = save(result, snapshot, started);
			for (var horizon : call.forecasts()) {
				if (horizon.horizonTradingSessions() == 1) continue;
				LlmForecastOutput value = validate(horizon.asForecastOutput());
				int sessions = horizon.horizonTradingSessions();
				LlmForecastResult additional = new LlmForecastResult(UUID.randomUUID(), analysisRunId,
						instrument.id(), instrument.symbol(), cutoffDate,
						new UsMarketCalendar().addTradingSessions(cutoffDate, sessions), "AVAILABLE",
						direction(value.upScore()), value.upScore(), value.confidence().toUpperCase(Locale.ROOT),
						clean(value.thesis(), 1_000), clean(value.bullishEvidence()), clean(value.bearishEvidence()),
						clean(value.risks()), clean(value.dataLimitations()), SCORE_INTERPRETATION, "OPENAI",
						call.model(), properties.getPromptVersion(), inputSha256, call.inputTokens(), call.outputTokens(),
						call.totalTokens(), elapsedMillis(started), null, null, Instant.now(clock), null,
						sessions, informationCutoff);
				save(additional, snapshot, started);
			}
			recordAudits(audits);
			return saved;
		}
		catch (RuntimeException exception) {
			String errorMessage = safeMessage(exception);
			log.warn("LLM forecast failed: analysisRunId={}, symbol={}, failureType={}, errorMessage={}",
					analysisRunId, instrument.symbol(), exception.getClass().getSimpleName(), errorMessage);
			LlmForecastResult failed = skipped(forecastId, analysisRunId, instrument, cutoffDate, targetSession, "FAILED",
					exception.getClass().getSimpleName(), errorMessage, inputSha256, started);
			return save(failed, snapshot, started);
		}
	}

	private Map<String, Object> filterSnapshot(LlmCandidateFilterDecision decision) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("enabled", true);
		value.put("eligible", decision.eligible());
		value.put("method", decision.method());
		value.put("positiveSignalCount", decision.positiveSignalCount());
		value.put("technicalSignalCount", decision.technicalSignalCount());
		if (decision.probabilityUp() != null) value.put("probabilityUp", decision.probabilityUp());
		value.put("reasons", decision.reasons());
		return Collections.unmodifiableMap(value);
	}

	public List<LlmForecastResult> history(String symbol, int limit) {
		String normalized = symbol == null ? "" : symbol.strip().toUpperCase(Locale.ROOT);
		return repository.findBySymbol(normalized, Math.max(1, Math.min(limit, 100)));
	}

	public List<LlmForecastResult> forAnalysisRun(UUID analysisRunId) {
		return repository.findByAnalysisRun(analysisRunId);
	}

	public BatchLlmRankingResponse rankings(UUID batchAnalysisId, String batchStatus, int horizon) {
		if (horizon != 1 && horizon != 5) throw new ApiException(HttpStatus.BAD_REQUEST,
				"INVALID_FORECAST_HORIZON", "horizon must be 1 or 5 trading sessions");
		List<BatchLlmRankingItem> rankings = repository.findBatchRankings(batchAnalysisId, horizon);
		return new BatchLlmRankingResponse(batchAnalysisId, batchStatus, SCORE_INTERPRETATION,
				"Experimental research ranking only; the score is not a calibrated probability or a recommendation to buy.",
				rankings);
	}

	public BatchLlmRankingResponse rankings(UUID batchAnalysisId, String batchStatus) {
		return rankings(batchAnalysisId, batchStatus, 1);
	}

	public LlmForecastPerformanceSummary performanceSummary() {
		return repository.performanceSummary();
	}

	public List<LlmInformationGapSummary> informationGaps(String status, int limit) {
		String normalized = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
		if (normalized != null && !GAP_WORKFLOW_STATUSES.contains(normalized)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INFORMATION_GAP_STATUS",
					"Unsupported information-gap status: " + normalized);
		}
		return gapRepository.find(normalized, Math.max(1, Math.min(limit, 500)));
	}

	public void updateInformationGap(String fingerprint, String status, String resolutionNotes) {
		String normalized = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
		if (!GAP_WORKFLOW_STATUSES.contains(normalized)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INFORMATION_GAP_STATUS",
					"Unsupported information-gap status: " + normalized);
		}
		if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_INFORMATION_GAP_FINGERPRINT",
					"Information-gap fingerprint must be a 64-character SHA-256 value");
		}
		if (!gapRepository.update(fingerprint, normalized, cleanNullable(resolutionNotes, 2_000))) {
			throw new ApiException(HttpStatus.NOT_FOUND, "INFORMATION_GAP_NOT_FOUND",
					"No information gap was found for " + fingerprint);
		}
	}

	private LlmForecastResult save(LlmForecastResult result, Map<String, Object> snapshot, long started) {
		repository.save(result, snapshot);
		meterRegistry.counter("stock.analysis.llm.forecast", "status", result.status()).increment();
		meterRegistry.timer("stock.analysis.llm.forecast.duration", "status", result.status())
				.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
		if (result.upScore() != null) meterRegistry.summary("stock.analysis.llm.up.score",
				"horizon", Integer.toString(result.horizonTradingSessions()), "direction", result.direction())
				.record(result.upScore());
		return result;
	}

	private LlmForecastResult skipped(UUID forecastId, UUID analysisRunId, Instrument instrument, LocalDate cutoffDate,
			LocalDate targetSession, String status, String errorCode, String message, String inputSha256, long started) {
		return new LlmForecastResult(forecastId, analysisRunId, instrument.id(), instrument.symbol(),
				cutoffDate, targetSession, status, "UNCERTAIN", null, "LOW", message,
				List.of(), List.of(), List.of(), List.of(message), SCORE_INTERPRETATION, "OPENAI",
				client.configuredModel(), properties.getPromptVersion(), inputSha256, null, null, null,
				elapsedMillis(started), errorCode, message, Instant.now(clock), null);
	}

	private Map<String, Object> inputSnapshot(Instrument instrument, LocalDate cutoffDate, LocalDate targetSession,
			String dataQualityStatus, List<AnalysisComponentResult> components, Instant informationCutoff) {
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("schemaVersion", 2);
		snapshot.put("instrumentId", instrument.id());
		snapshot.put("symbol", instrument.symbol());
		snapshot.put("companyName", instrument.name());
		snapshot.put("sector", instrument.sector());
		snapshot.put("industry", instrument.industry());
		snapshot.put("cutoffDate", cutoffDate);
		snapshot.put("targetSession", targetSession);
		snapshot.put("informationCutoff", informationCutoff);
		snapshot.put("horizon", "NEXT_COMPLETED_US_TRADING_SESSION");
		snapshot.put("dataQualityStatus", dataQualityStatus);
		snapshot.put("scoreDefinition", SCORE_INTERPRETATION);
		List<Map<String, Object>> evidence = new ArrayList<>();
		for (AnalysisComponentResult component : components) {
			if (component.key().startsWith("prediction.")) continue;
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("key", component.key());
			item.put("status", component.status());
			item.put("summary", component.summary());
			item.put("metrics", component.metrics());
			item.put("reasons", component.reasons());
			item.put("evidence", component.evidence());
			item.put("limitations", component.limitations());
			evidence.add(item);
		}
		snapshot.put("components", evidence);
		return Collections.unmodifiableMap(snapshot);
	}

	private List<Map<String, Object>> researchRequests(UUID forecastId, UUID analysisRunId, Instrument instrument,
			LocalDate cutoffDate, Instant informationCutoff, List<LlmInformationRequest> requests,
			List<LlmInformationRequestAudit> audits) {
		if (requests == null || requests.isEmpty()) return List.of();
		List<Map<String, Object>> evidence = new ArrayList<>();
		int maximum = Math.max(0, Math.min(properties.getWebResearch().getMaxRequestsPerForecast(), 3));
		int attempted = 0;
		for (LlmInformationRequest raw : requests.stream().limit(8).toList()) {
			LlmInformationRequest request = normalize(raw);
			String fingerprint = requestFingerprint(request);
			if (containsFingerprint(audits, fingerprint)) continue;
			String resolutionStatus;
			LlmWebResearchResult research = null;
			if (attempted >= maximum) {
				resolutionStatus = "RESEARCH_LIMIT_REACHED";
			}
			else if (!isSearchable(request)) {
				resolutionStatus = hasText(request.searchQuery()) ? "WEB_NOT_ALLOWED" : "NOT_SEARCHABLE";
			}
			else if (!webResearchClient.isAvailable()) {
				resolutionStatus = "WEB_DISABLED";
			}
			else {
				attempted++;
				research = webResearchClient.research(instrument.symbol(), informationCutoff, request);
				resolutionStatus = "AVAILABLE".equals(research.status()) ? "WEB_RESOLVED" : "WEB_FAILED";
				if ("WEB_RESOLVED".equals(resolutionStatus)) evidence.add(webEvidence(request, research));
			}
			audits.add(audit(forecastId, analysisRunId, instrument, cutoffDate, request, fingerprint,
					resolutionStatus, research));
		}
		return List.copyOf(evidence);
	}

	private void recordLimitReached(UUID forecastId, UUID analysisRunId, Instrument instrument, LocalDate cutoffDate,
			List<LlmInformationRequest> requests, List<LlmInformationRequestAudit> audits) {
		if (requests == null) return;
		for (LlmInformationRequest raw : requests.stream().limit(8).toList()) {
			LlmInformationRequest request = normalize(raw);
			String fingerprint = requestFingerprint(request);
			if (containsFingerprint(audits, fingerprint)) continue;
			audits.add(audit(forecastId, analysisRunId, instrument, cutoffDate, request,
					fingerprint, "RESEARCH_LIMIT_REACHED", null));
		}
	}

	private LlmForecastOutput addUnresolvedLimitations(LlmForecastOutput output,
			List<LlmInformationRequestAudit> audits) {
		List<String> limitations = new ArrayList<>(output.dataLimitations());
		audits.stream().filter(audit -> !"WEB_RESOLVED".equals(audit.resolutionStatus()))
				.map(audit -> "Additional information unavailable: " + audit.request().dataRequested())
				.map(value -> clean(value, 500)).forEach(limitations::add);
		return new LlmForecastOutput(output.upScore(), output.confidence(), output.thesis(),
				output.bullishEvidence(), output.bearishEvidence(), output.risks(),
				limitations.stream().distinct().limit(8).toList(), output.informationRequests());
	}

	private void recordAudits(List<LlmInformationRequestAudit> audits) {
		for (LlmInformationRequestAudit audit : audits) {
			try {
				gapRepository.record(audit);
			}
			catch (RuntimeException exception) {
				log.error("LLM information request audit could not be persisted: forecastId={}, fingerprint={}, error={}",
						audit.forecastId(), audit.fingerprint(), safeMessage(exception));
			}
		}
	}

	private LlmInformationRequestAudit audit(UUID forecastId, UUID analysisRunId, Instrument instrument,
			LocalDate cutoffDate, LlmInformationRequest request, String fingerprint, String resolutionStatus,
			LlmWebResearchResult research) {
		return new LlmInformationRequestAudit(UUID.randomUUID(), forecastId, analysisRunId, instrument.id(),
				instrument.symbol(), cutoffDate, request, fingerprint, resolutionStatus, research, Instant.now(clock));
	}

	private LlmForecastCall combine(LlmForecastCall first, LlmForecastCall second) {
		return new LlmForecastCall(second.output(), second.model(), add(first.inputTokens(), second.inputTokens()),
				add(first.outputTokens(), second.outputTokens()), add(first.totalTokens(), second.totalTokens()),
				second.forecasts(), java.util.stream.Stream.concat(first.interactions().stream(),
						second.interactions().stream()).toList());
	}

	private Long add(Long first, Long second) {
		if (first == null && second == null) return null;
		return (first == null ? 0L : first) + (second == null ? 0L : second);
	}

	private Map<String, Object> webEvidence(LlmInformationRequest request, LlmWebResearchResult research) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("category", request.category());
		value.put("dataRequested", request.dataRequested());
		value.put("summary", research.summary());
		value.put("sources", research.sources());
		value.put("provider", "OPENAI_WEB_SEARCH");
		value.put("model", research.model());
		return Collections.unmodifiableMap(value);
	}

	private LlmInformationRequest normalize(LlmInformationRequest request) {
		if (request == null) {
			return new LlmInformationRequest("OTHER", "Unspecified additional information",
					"The model returned an empty information request", "LOW", "UNSPECIFIED", "");
		}
		String category = enumValue(request.category(), "OTHER", Set.of(
				"NEWS", "REGULATORY", "COMPANY_EVENT", "EARNINGS", "MACRO", "OPTIONS",
				"SHORT_INTEREST", "INSIDER", "OTHER"));
		String importance = enumValue(request.importance(), "MEDIUM", IMPORTANCE_LEVELS);
		return new LlmInformationRequest(category,
				nonBlank(request.dataRequested(), "Unspecified additional information", 500),
				nonBlank(request.reason(), "No reason supplied", 500), importance,
				nonBlank(request.preferredSourceType(), "UNSPECIFIED", 100),
				cleanNullable(request.searchQuery(), 500));
	}

	private boolean isSearchable(LlmInformationRequest request) {
		if (!hasText(request.searchQuery())) return false;
		return properties.getWebResearch().getSearchableCategories().stream()
				.filter(this::hasText).map(value -> value.strip().toUpperCase(Locale.ROOT))
				.anyMatch(request.category()::equals);
	}

	private String requestFingerprint(LlmInformationRequest request) {
		return sha256((request.category() + "|" + request.dataRequested() + "|" + request.preferredSourceType())
				.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip());
	}

	private String enumValue(String value, String fallback, Set<String> supported) {
		String normalized = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
		return supported.contains(normalized) ? normalized : fallback;
	}

	private String nonBlank(String value, String fallback, int maximumLength) {
		return hasText(value) ? clean(value, maximumLength) : fallback;
	}

	private String cleanNullable(String value, int maximumLength) {
		return value == null ? null : clean(value, maximumLength);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private boolean containsFingerprint(List<LlmInformationRequestAudit> audits, String fingerprint) {
		return audits.stream().anyMatch(audit -> audit.fingerprint().equals(fingerprint));
	}

	private LlmForecastOutput validate(LlmForecastOutput output) {
		if (output == null) throw new IllegalArgumentException("Structured forecast output is missing");
		if (output.upScore() < 0 || output.upScore() > 100) {
			throw new IllegalArgumentException("upScore must be between 0 and 100");
		}
		String confidence = output.confidence() == null ? "" : output.confidence().toUpperCase(Locale.ROOT);
		if (!CONFIDENCE_LEVELS.contains(confidence)) {
			throw new IllegalArgumentException("confidence must be LOW, MEDIUM, or HIGH");
		}
		if (output.thesis() == null || output.thesis().isBlank()) {
			throw new IllegalArgumentException("thesis is required");
		}
		return output;
	}

	private String direction(int score) {
		if (score >= properties.getUpThreshold()) return "UP";
		if (score <= properties.getDownThreshold()) return "DOWN";
		return "UNCERTAIN";
	}

	private List<String> clean(List<String> values) {
		if (values == null) return List.of();
		return values.stream().filter(value -> value != null && !value.isBlank())
				.map(value -> clean(value, 500)).limit(8).toList();
	}

	private String clean(String value, int maxLength) {
		String cleaned = value.replaceAll("\\s+", " ").strip();
		return cleaned.substring(0, Math.min(maxLength, cleaned.length()));
	}

	private String safeMessage(RuntimeException exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
		String sanitized = SENSITIVE_VALUE.matcher(message).replaceAll("$1$2***")
				.replaceAll("\\s+", " ").strip();
		return sanitized.substring(0, Math.min(1_000, sanitized.length()));
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("LLM forecast input could not be serialized", exception);
		}
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private void validateConfiguration() {
		if (properties.getDownThreshold() < 0 || properties.getUpThreshold() > 100
				|| properties.getDownThreshold() >= properties.getUpThreshold()) {
			throw new IllegalArgumentException("LLM forecast thresholds must satisfy 0 <= down < up <= 100");
		}
	}

	private long elapsedMillis(long started) {
		return (System.nanoTime() - started) / 1_000_000L;
	}
}
