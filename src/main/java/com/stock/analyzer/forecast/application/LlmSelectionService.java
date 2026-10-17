package com.stock.analyzer.forecast.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.stock.analyzer.api.error.ApiException;
import com.stock.analyzer.batch.application.BatchAnalysisService;
import com.stock.analyzer.configuration.LlmSelectionProperties;
import com.stock.analyzer.forecast.domain.LlmSelectionCandidateInput;
import com.stock.analyzer.forecast.domain.LlmSelectionCandidateResult;
import com.stock.analyzer.forecast.domain.LlmSelectionExcludedCandidate;
import com.stock.analyzer.forecast.domain.LlmSelectionOutput;
import com.stock.analyzer.forecast.domain.LlmSelectionOutputCandidate;
import com.stock.analyzer.forecast.domain.LlmSelectionState;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class LlmSelectionService {

	private static final Logger log = LoggerFactory.getLogger(LlmSelectionService.class);
	private static final int MAX_CANDIDATE_LIMIT = 50;
	private final LlmSelectionRepository repository;
	private final BatchAnalysisService batchAnalysisService;
	private final OpenAiLlmSelectionClient client;
	private final LlmSelectionProperties properties;
	private final ObjectMapper mapper;
	private final ExecutorService executor;
	private final MeterRegistry meters;

	public LlmSelectionService(LlmSelectionRepository repository, BatchAnalysisService batchAnalysisService,
			OpenAiLlmSelectionClient client, LlmSelectionProperties properties, ObjectMapper mapper,
			ExecutorService executor, MeterRegistry meters) {
		this.repository = repository;
		this.batchAnalysisService = batchAnalysisService;
		this.client = client;
		this.properties = properties;
		this.mapper = mapper;
		this.executor = executor;
		this.meters = meters;
	}

	public LlmSelectionSubmission submit(UUID batchAnalysisId, int horizonTradingSessions,
			boolean forceReselection) {
		validateConfiguration(horizonTradingSessions);
		var batch = batchAnalysisService.get(batchAnalysisId);
		if ("QUEUED".equals(batch.status()) || "RUNNING".equals(batch.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "BATCH_NOT_FINISHED",
					"Candidate selection can start only after the batch has finished");
		}

		int candidateLimit = Math.max(3, Math.min(properties.getCandidateLimit(), MAX_CANDIDATE_LIMIT));
		int selectionLimit = Math.max(1, Math.min(properties.getSelectionLimit(), candidateLimit));
		String selectionPromptIdentity = selectionPromptIdentity();
		if (!forceReselection) {
			var reusable = repository.findReusable(batchAnalysisId, horizonTradingSessions, candidateLimit,
					selectionLimit, properties.getModel(), selectionPromptIdentity);
			if (reusable.isPresent()) {
				LlmSelectionState state = withReuse(reusable.get(), true);
				return new LlmSelectionSubmission(state.selectionId(), CompletableFuture.completedFuture(state), true);
			}
		}

		List<LlmSelectionCandidateInput> available = repository.loadCandidates(batchAnalysisId,
				horizonTradingSessions, candidateLimit);
		if (available.size() < selectionLimit) {
			throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_SELECTION_CANDIDATES",
					"The batch has " + available.size() + " available horizon-" + horizonTradingSessions
							+ " forecasts; at least " + selectionLimit + " are required");
		}

		UUID selectionId = repository.create(batchAnalysisId, horizonTradingSessions, candidateLimit,
				selectionLimit, properties.getModel(), selectionPromptIdentity);
		CompletableFuture<LlmSelectionState> completion = CompletableFuture.supplyAsync(
				() -> execute(selectionId, batchAnalysisId, horizonTradingSessions, candidateLimit, selectionLimit),
				executor);
		return new LlmSelectionSubmission(selectionId, completion, false);
	}

	public LlmSelectionState get(UUID selectionId) {
		return repository.find(selectionId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"LLM_SELECTION_NOT_FOUND", "No LLM candidate selection was found for " + selectionId));
	}

	private LlmSelectionState execute(UUID selectionId, UUID batchAnalysisId, int horizon,
			int candidateLimit, int selectionLimit) {
		long started = System.nanoTime();
		repository.markRunning(selectionId);
		try {
			List<LlmSelectionCandidateInput> candidates = repository.loadCandidates(batchAnalysisId, horizon,
					candidateLimit);
			if (candidates.size() < selectionLimit) {
				throw new IllegalStateException("Available candidate count fell below the configured selection limit");
			}
			Map<String, Object> snapshot = inputSnapshot(batchAnalysisId, horizon, selectionLimit, candidates);
			var call = client.select(snapshot, candidates, selectionLimit);
			String inputHash = sha256(json(call.inputSnapshot()));
			List<LlmSelectionCandidateResult> results = validateAndMerge(candidates, call.output(), selectionLimit);
			long duration = elapsed(started);
			repository.complete(selectionId, call.inputSnapshot(), inputHash, call, results, duration);
			meters.counter("stock.analysis.llm.selection.runs", "status", "completed").increment();
			meters.timer("stock.analysis.llm.selection.duration", "status", "completed")
					.record(duration, TimeUnit.MILLISECONDS);
			log.info("LLM candidate selection completed: selectionId={}, batchId={}, candidates={}, selected={}, "
						+ "horizon={}, model={}, durationMs={}", selectionId, batchAnalysisId, candidates.size(),
					results.stream().filter(LlmSelectionCandidateResult::selected).count(), horizon, call.model(), duration);
		}
		catch (RuntimeException exception) {
			long duration = elapsed(started);
			String code = exception.getClass().getSimpleName();
			String message = safeMessage(exception);
			repository.fail(selectionId, code, message, duration);
			meters.counter("stock.analysis.llm.selection.runs", "status", "failed").increment();
			meters.timer("stock.analysis.llm.selection.duration", "status", "failed")
					.record(duration, TimeUnit.MILLISECONDS);
			log.error("LLM candidate selection failed: selectionId={}, batchId={}, error={}",
					selectionId, batchAnalysisId, message, exception);
		}
		return get(selectionId);
	}

	private void validateConfiguration(int horizon) {
		if (horizon != 1 && horizon != 5) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_SELECTION_HORIZON",
					"horizonTradingSessions must be either 1 or 5");
		}
		if (!client.isAvailable()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "LLM_SELECTION_NOT_CONFIGURED",
					"Enable LLM selection and configure LLM_SELECTION_MODEL and OPENAI_API_KEY");
		}
	}

	private Map<String, Object> inputSnapshot(UUID batchId, int horizon, int selectionLimit,
			List<LlmSelectionCandidateInput> candidates) {
		List<Map<String, Object>> summaries = new ArrayList<>();
		for (var candidate : candidates) {
			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("originalRank", candidate.originalRank());
			summary.put("symbol", candidate.symbol());
			summary.put("stockName", candidate.stockName());
			summary.put("sector", candidate.sector());
			summary.put("cutoffDate", candidate.cutoffDate());
			summary.put("targetSession", candidate.targetSession());
			summary.put("informationCutoff", candidate.informationCutoff());
			summary.put("upScore", candidate.upScore());
			summary.put("confidence", candidate.confidence());
			summary.put("thesis", candidate.thesis());
			summary.put("dataQualityStatus", candidate.dataQualityStatus());
			summary.put("sourceModel", candidate.model());
			summary.put("sourcePromptVersion", candidate.promptVersion());
			summary.put("sourceAnalysisInput", candidate.inputSnapshot());
			summary.put("sourceForecastOutput", candidate.responsePayload());
			summaries.add(summary);
		}
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("objective", "Rank the strongest relative candidates; the score is not a calibrated probability");
		snapshot.put("batchAnalysisId", batchId);
		snapshot.put("horizonTradingSessions", horizon);
		snapshot.put("selectionLimit", selectionLimit);
		snapshot.put("candidateSymbols", candidates.stream().map(LlmSelectionCandidateInput::symbol).toList());
		snapshot.put("candidates", summaries);
		return snapshot;
	}

	private List<LlmSelectionCandidateResult> validateAndMerge(List<LlmSelectionCandidateInput> candidates,
			LlmSelectionOutput output, int selectionLimit) {
		if (output == null) throw new IllegalStateException("Selection output is missing");
		if (output.selectedCandidates().size() != selectionLimit) {
			throw new IllegalStateException("OpenAI must select exactly " + selectionLimit + " candidates");
		}
		Map<String, LlmSelectionCandidateInput> inputs = new LinkedHashMap<>();
		for (var candidate : candidates) inputs.put(normalize(candidate.symbol()), candidate);
		Map<String, LlmSelectionOutputCandidate> selected = new HashMap<>();
		Set<Integer> ranks = new HashSet<>();
		for (var result : output.selectedCandidates()) {
			String symbol = normalize(result.symbol());
			if (!inputs.containsKey(symbol)) throw new IllegalStateException("Selected symbol is not a candidate: " + symbol);
			if (selected.putIfAbsent(symbol, result) != null) throw new IllegalStateException("Duplicate selected symbol: " + symbol);
			if (result.rank() < 1 || result.rank() > selectionLimit || !ranks.add(result.rank())) {
				throw new IllegalStateException("Selected ranks must be unique values from 1 to " + selectionLimit);
			}
			if (result.selectionScore() < 0 || result.selectionScore() > 100) {
				throw new IllegalStateException("Selection score must be from 0 to 100");
			}
		}
		Map<String, String> exclusions = new HashMap<>();
		for (LlmSelectionExcludedCandidate excluded : output.notSelected()) {
			String symbol = normalize(excluded.symbol());
			if (inputs.containsKey(symbol) && !selected.containsKey(symbol)) exclusions.putIfAbsent(symbol, excluded.reason());
		}

		List<LlmSelectionCandidateResult> merged = new ArrayList<>();
		for (var input : candidates) {
			String symbol = normalize(input.symbol());
			var chosen = selected.get(symbol);
			if (chosen != null) {
				merged.add(new LlmSelectionCandidateResult(input.forecastId(), input.symbol(), input.originalRank(),
						input.upScore(), input.confidence(), true, chosen.rank(), chosen.selectionScore(),
						chosen.confidence(), chosen.comparativeThesis(), chosen.bullishEvidence(),
						chosen.bearishEvidence(), chosen.comparativeAdvantages(), chosen.invalidationRisks(),
						chosen.dataLimitations(), null));
			}
			else {
				merged.add(new LlmSelectionCandidateResult(input.forecastId(), input.symbol(), input.originalRank(),
						input.upScore(), input.confidence(), false, null, null, null, null, List.of(), List.of(),
						List.of(), List.of(), List.of(), exclusions.getOrDefault(symbol, "Not selected in comparative ranking")));
			}
		}
		merged.sort(Comparator.comparing(LlmSelectionCandidateResult::selected).reversed()
				.thenComparing(result -> result.selectedRank() == null ? Integer.MAX_VALUE : result.selectedRank())
				.thenComparingInt(LlmSelectionCandidateResult::originalRank));
		return List.copyOf(merged);
	}

	private LlmSelectionState withReuse(LlmSelectionState state, boolean reused) {
		return new LlmSelectionState(state.selectionId(), state.batchAnalysisId(), state.status(),
				state.horizonTradingSessions(), state.candidateLimit(), state.selectionLimit(), state.candidateCount(),
				state.selectedCount(), state.scoreInterpretation(), state.model(), state.promptVersion(),
				state.inputTokens(), state.outputTokens(), state.totalTokens(), state.durationMs(), state.errorCode(),
				state.errorMessage(), reused, state.createdAt(), state.completedAt(), state.candidates(),
				state.portfolioLevelRisks(), state.overallLimitations(), state.webResearch());
	}

	private String normalize(String symbol) {
		return symbol == null ? "" : symbol.strip().toUpperCase(Locale.ROOT);
	}

	private String selectionPromptIdentity() {
		if (!properties.isWebResearchEnabled()) return properties.getPromptVersion() + "+local-v1";
		String researchConfiguration = properties.getWebResearchModel() + '|'
				+ properties.getWebResearchReasoningEffort() + '|' + properties.getWebResearchMaxRequests() + '|'
				+ properties.getWebResearchAllowedDomains();
		return properties.getPromptVersion() + "+web-" + sha256(researchConfiguration).substring(0, 12);
	}

	private String json(Object value) {
		try { return mapper.writeValueAsString(value); }
		catch (JacksonException exception) { throw new IllegalStateException("Selection input could not be serialized", exception); }
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
	}

	private long elapsed(long started) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
	}

	private String safeMessage(RuntimeException exception) {
		String message = exception.getMessage();
		if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
		return message.substring(0, Math.min(message.length(), 4_000));
	}
}
