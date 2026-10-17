package com.stock.analyzer.forecast.application;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.stock.analyzer.configuration.LlmSelectionProperties;
import com.stock.analyzer.forecast.domain.AiInteractionAudit;
import com.stock.analyzer.forecast.domain.AiToolCallAudit;
import com.stock.analyzer.forecast.domain.LlmSelectionCall;
import com.stock.analyzer.forecast.domain.LlmSelectionCandidateInput;
import com.stock.analyzer.forecast.domain.LlmSelectionOutput;
import com.stock.analyzer.forecast.domain.LlmSelectionWebResearch;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A separate Responses API client for cross-sectional candidate selection. */
@Component
public class OpenAiLlmSelectionClient {

	private static final int HARD_MAX_DATABASE_CALLS = 20;
	private static final int HARD_MAX_ROUNDS = 25;
	private static final int HARD_MAX_WEB_REQUESTS = 50;
	private static final List<String> TOOLS = List.of("get_candidate_available_data", "get_candidate_price_history",
			"get_candidate_fundamental_history", "get_candidate_market_context", "get_candidate_sector_context");
	private final LlmSelectionProperties properties;
	private final LlmDatabaseToolExecutor executor;
	private final OpenAiLlmSelectionWebResearchClient webResearchClient;
	private final ObjectMapper mapper;
	private final MeterRegistry meters;
	private final String instructions;
	private final RestClient rest;

	public OpenAiLlmSelectionClient(LlmSelectionProperties properties, LlmDatabaseToolExecutor executor,
			OpenAiLlmSelectionWebResearchClient webResearchClient, ObjectMapper mapper, MeterRegistry meters,
			LlmForecastPromptCatalog prompts) {
		this.properties = properties;
		this.executor = executor;
		this.webResearchClient = webResearchClient;
		this.mapper = mapper;
		this.meters = meters;
		this.instructions = prompts.load(properties.getPromptVersion());
		HttpClient http = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(properties.getReadTimeout());
		this.rest = RestClient.builder().baseUrl(properties.getBaseUrl().replaceAll("/+$", ""))
				.requestFactory(factory).build();
	}

	public boolean isAvailable() {
		return properties.isEnabled() && text(properties.getApiKey()) && text(properties.getModel());
	}

	public LlmSelectionCall select(Map<String, Object> inputSnapshot, List<LlmSelectionCandidateInput> candidates,
			int selectionLimit) {
		if (!isAvailable()) throw new IllegalStateException("LLM candidate selection is not configured");
		List<LlmSelectionWebResearch> webResearch = researchAllCandidates(candidates,
				((Number) inputSnapshot.getOrDefault("horizonTradingSessions", 5)).intValue());
		Map<String, Object> enrichedInput = new LinkedHashMap<>(inputSnapshot);
		enrichedInput.put("selectionInformationCutoff", Instant.now());
		enrichedInput.put("webResearch", webResearch);
		Map<String, LlmSelectionCandidateInput> allowed = new LinkedHashMap<>();
		for (var candidate : candidates) allowed.put(candidate.symbol().toUpperCase(Locale.ROOT), candidate);
		List<Object> conversation = new ArrayList<>();
		conversation.add(Map.of("role", "user", "content", json(enrichedInput)));
		List<AiInteractionAudit> interactions = researchInteractions(webResearch, candidates,
				((Number) inputSnapshot.getOrDefault("horizonTradingSessions", 5)).intValue());
		Map<String, LlmDatabaseToolExecutor.ToolResult> cache = new LinkedHashMap<>();
		long allInput = webResearch.stream().mapToLong(value -> zero(value.inputTokens())).sum();
		long allOutput = webResearch.stream().mapToLong(value -> zero(value.outputTokens())).sum();
		long allTotal = webResearch.stream().mapToLong(value -> zero(value.totalTokens())).sum();
		int databaseCalls = 0;
		int roundOffset = interactions.size();
		String responseModel = properties.getModel();
		LlmSelectionOutput output = null;

		for (int round = 1; round <= maxRounds(); round++) {
			long started = System.nanoTime();
			JsonNode response = invoke(conversation, selectionLimit);
			long duration = elapsed(started);
			responseModel = response.path("model").asText(properties.getModel());
			JsonNode usage = response.path("usage");
			Long inputTokens = number(usage, "input_tokens");
			Long outputTokens = number(usage, "output_tokens");
			Long totalTokens = number(usage, "total_tokens");
			allInput += zero(inputTokens); allOutput += zero(outputTokens); allTotal += zero(totalTokens);
			List<Object> toolOutputs = new ArrayList<>();
			List<AiToolCallAudit> toolAudits = new ArrayList<>();

			for (JsonNode item : response.path("output")) {
				conversation.add(mapper.readValue(item.toString(), Object.class));
				if (!"function_call".equals(item.path("type").asText())) continue;
				String name = item.path("name").asText();
				String callId = item.path("call_id").asText();
				String arguments = item.path("arguments").asText("{}");
				long toolStarted = System.nanoTime();
				String status = "COMPLETED", errorCode = null, errorMessage = null, resultJson;
				Integer resultCount = null;
				try {
					if (databaseCalls >= maxDatabaseCalls()) throw new IllegalStateException("Selection tool budget exhausted");
					databaseCalls++;
					if (!TOOLS.contains(name)) throw new IllegalArgumentException("Unsupported selection tool: " + name);
					JsonNode parsed = mapper.readTree(arguments);
					String symbol = parsed.path("symbol").asText().strip().toUpperCase(Locale.ROOT);
					LlmSelectionCandidateInput candidate = allowed.get(symbol);
					if (candidate == null) throw new IllegalArgumentException("Symbol is outside this selection: " + symbol);
					int limit = parsed.path("limit").asInt(20);
					String cacheKey = name + '|' + symbol + '|' + limit;
					LlmDatabaseToolExecutor.ToolResult result = cache.get(cacheKey);
					if (result == null) {
						result = executor.execute(baseToolName(name), candidate.instrumentId(), candidate.cutoffDate(), limit);
						cache.put(cacheKey, result);
					}
					else status = "CACHED";
					resultJson = result.json(); resultCount = result.resultCount();
				}
				catch (RuntimeException exception) {
					status = "FAILED"; errorCode = exception.getClass().getSimpleName(); errorMessage = safe(exception.getMessage());
					resultJson = json(Map.of("error", errorCode, "message", errorMessage));
				}
				long toolDuration = elapsed(toolStarted);
				toolOutputs.add(Map.of("type", "function_call_output", "call_id", callId, "output", resultJson));
				toolAudits.add(new AiToolCallAudit(callId, "DATABASE", name, status, toolDuration, resultCount,
						sha256(arguments), errorCode, errorMessage));
				meters.counter("stock.analysis.llm.selection.tool.calls", "tool", name, "status", status).increment();
				meters.timer("stock.analysis.llm.selection.tool.duration", "tool", name, "status", status)
						.record(toolDuration, TimeUnit.MILLISECONDS);
			}

			interactions.add(new AiInteractionAudit(response.path("id").asText(null), responseModel, roundOffset + round,
					"COMPLETED", inputTokens, outputTokens, totalTokens, duration, toolOutputs.size(), 0,
					null, null, toolAudits));
			recordRound(responseModel, inputTokens, outputTokens, totalTokens, duration);
			if (toolOutputs.isEmpty()) {
				String finalText = outputText(response);
				if (!finalText.isBlank()) output = mapper.readValue(finalText, LlmSelectionOutput.class);
				if (output != null) break;
			}
			conversation.addAll(toolOutputs);
		}

		if (output == null) throw new IllegalStateException("OpenAI returned no structured candidate selection");
		return new LlmSelectionCall(output, responseModel, allInput, allOutput, allTotal, interactions,
				webResearch, enrichedInput);
	}

	private List<LlmSelectionWebResearch> researchAllCandidates(List<LlmSelectionCandidateInput> candidates,
			int horizon) {
		if (!properties.isWebResearchEnabled()) return List.of();
		if (!webResearchClient.isAvailable()) {
			if (properties.isWebResearchRequired()) {
				throw new IllegalStateException("Selection web research is enabled but is not fully configured");
			}
			return List.of();
		}
		int requests = maxWebRequests();
		if (properties.isWebResearchRequired() && requests < candidates.size()) {
			throw new IllegalStateException("Selection web research request limit " + requests
					+ " is lower than candidate count " + candidates.size());
		}
		List<LlmSelectionWebResearch> results = new ArrayList<>();
		for (LlmSelectionCandidateInput candidate : candidates.stream().limit(requests).toList()) {
			results.add(webResearchClient.research(candidate, horizon));
		}
		return List.copyOf(results);
	}

	private List<AiInteractionAudit> researchInteractions(List<LlmSelectionWebResearch> research,
			List<LlmSelectionCandidateInput> candidates, int horizon) {
		Map<String, LlmSelectionCandidateInput> bySymbol = new LinkedHashMap<>();
		for (var candidate : candidates) bySymbol.put(candidate.symbol().toUpperCase(Locale.ROOT), candidate);
		List<AiInteractionAudit> interactions = new ArrayList<>();
		int round = 0;
		for (var result : research) {
			var candidate = bySymbol.get(result.symbol().toUpperCase(Locale.ROOT));
			String hash = candidate == null ? null : webResearchClient.requestHash(candidate, horizon);
			var tool = new AiToolCallAudit(result.responseId(), "WEB_SEARCH", "web_search", result.status(),
					result.durationMs(), result.sources().size(), hash, result.errorCode(), result.errorMessage());
			interactions.add(new AiInteractionAudit(result.responseId(), result.model(), ++round, result.status(),
					result.inputTokens(), result.outputTokens(), result.totalTokens(), result.durationMs(), 0,
					result.webSearchCount(), result.errorCode(), result.errorMessage(), List.of(tool)));
		}
		return interactions;
	}

	private JsonNode invoke(List<Object> input, int selectionLimit) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", properties.getModel());
		body.put("reasoning", Map.of("effort", properties.getReasoningEffort()));
		body.put("instructions", instructions);
		body.put("input", input);
		body.put("tools", toolDefinitions());
		body.put("tool_choice", "auto");
		body.put("text", Map.of("format", Map.of("type", "json_schema", "name", "candidate_selection",
				"strict", true, "schema", outputSchema(selectionLimit))));
		body.put("include", List.of("reasoning.encrypted_content"));
		body.put("store", false);
		String value = rest.post().uri("/responses").header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
				.contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
		if (!text(value)) throw new IllegalStateException("OpenAI returned an empty selection response");
		try { return mapper.readTree(value); }
		catch (JacksonException exception) { throw new IllegalStateException("Invalid OpenAI selection JSON", exception); }
	}

	private List<Map<String, Object>> toolDefinitions() {
		List<Map<String, Object>> definitions = new ArrayList<>();
		for (String name : TOOLS) {
			Map<String, Object> propertiesSchema = new LinkedHashMap<>();
			propertiesSchema.put("symbol", Map.of("type", "string", "description", "A symbol in candidateSymbols"));
			propertiesSchema.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 120));
			definitions.add(Map.of("type", "function", "name", name, "description", description(name), "strict", true,
					"parameters", Map.of("type", "object", "properties", propertiesSchema,
							"required", List.of("symbol", "limit"), "additionalProperties", false)));
		}
		return definitions;
	}

	private Map<String, Object> outputSchema(int selectionLimit) {
		Map<String, Object> strings = Map.of("type", "array", "items", Map.of("type", "string"));
		Map<String, Object> selectedProperties = new LinkedHashMap<>();
		selectedProperties.put("rank", Map.of("type", "integer", "minimum", 1, "maximum", selectionLimit));
		selectedProperties.put("symbol", Map.of("type", "string"));
		selectedProperties.put("selectionScore", Map.of("type", "integer", "minimum", 0, "maximum", 100));
		selectedProperties.put("confidence", Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH")));
		selectedProperties.put("comparativeThesis", Map.of("type", "string"));
		selectedProperties.put("bullishEvidence", strings);
		selectedProperties.put("bearishEvidence", strings);
		selectedProperties.put("comparativeAdvantages", strings);
		selectedProperties.put("invalidationRisks", strings);
		selectedProperties.put("dataLimitations", strings);
		Map<String, Object> selected = Map.of("type", "object", "properties", selectedProperties,
				"required", List.copyOf(selectedProperties.keySet()), "additionalProperties", false);
		Map<String, Object> excluded = Map.of("type", "object", "properties", Map.of(
				"symbol", Map.of("type", "string"), "reason", Map.of("type", "string")),
				"required", List.of("symbol", "reason"), "additionalProperties", false);
		Map<String, Object> rootProperties = new LinkedHashMap<>();
		rootProperties.put("selectedCandidates", Map.of("type", "array", "items", selected,
				"minItems", selectionLimit, "maxItems", selectionLimit));
		rootProperties.put("notSelected", Map.of("type", "array", "items", excluded));
		rootProperties.put("portfolioLevelRisks", strings);
		rootProperties.put("overallLimitations", strings);
		return Map.of("type", "object", "properties", rootProperties, "required", List.copyOf(rootProperties.keySet()),
				"additionalProperties", false);
	}

	private String baseToolName(String name) {
		return switch (name) {
			case "get_candidate_available_data" -> "get_available_stock_data";
			case "get_candidate_price_history" -> "get_price_history";
			case "get_candidate_fundamental_history" -> "get_fundamental_history";
			case "get_candidate_market_context" -> "get_market_context";
			case "get_candidate_sector_context" -> "get_sector_context";
			default -> throw new IllegalArgumentException("Unsupported selection tool: " + name);
		};
	}

	private String description(String name) {
		return switch (name) {
			case "get_candidate_available_data" -> "Count point-in-time data available for one candidate.";
			case "get_candidate_price_history" -> "Get bounded daily OHLCV history for one candidate through its cutoff.";
			case "get_candidate_fundamental_history" -> "Get bounded fundamentals for one candidate through its cutoff.";
			case "get_candidate_market_context" -> "Get bounded SPY closes through the candidate cutoff.";
			case "get_candidate_sector_context" -> "Get bounded sector ETF closes through the candidate cutoff.";
			default -> name;
		};
	}

	int maxDatabaseCalls() { return Math.max(0, Math.min(properties.getMaxDatabaseToolCalls(), HARD_MAX_DATABASE_CALLS)); }
	int maxRounds() { return Math.max(2, Math.min(properties.getMaxRounds(), HARD_MAX_ROUNDS)); }
	int maxWebRequests() { return Math.max(0, Math.min(properties.getWebResearchMaxRequests(), HARD_MAX_WEB_REQUESTS)); }

	private String outputText(JsonNode root) {
		StringBuilder value = new StringBuilder();
		for (JsonNode item : root.path("output")) for (JsonNode part : item.path("content"))
			if ("output_text".equals(part.path("type").asText())) value.append(part.path("text").asText());
		return value.toString();
	}

	private void recordRound(String model, Long input, Long output, Long total, long duration) {
		meters.counter("stock.analysis.llm.selection.responses", "model", model, "status", "success").increment();
		meters.timer("stock.analysis.llm.selection.response.duration", "model", model, "status", "success")
				.record(duration, TimeUnit.MILLISECONDS);
		meters.counter("stock.analysis.llm.selection.tokens", "model", model, "type", "input").increment(zero(input));
		meters.counter("stock.analysis.llm.selection.tokens", "model", model, "type", "output").increment(zero(output));
		meters.counter("stock.analysis.llm.selection.tokens", "model", model, "type", "total").increment(zero(total));
	}

	private Long number(JsonNode node, String field) { return node.has(field) && node.get(field).isNumber() ? node.get(field).longValue() : null; }
	private long zero(Long value) { return value == null ? 0 : value; }
	private long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
	private boolean text(String value) { return value != null && !value.isBlank(); }
	private String safe(String value) { return value == null || value.isBlank() ? "Tool call failed" : value.substring(0, Math.min(1_000, value.length())); }
	private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (JacksonException e) { throw new IllegalStateException("Selection JSON serialization failed", e); } }
	private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); } }
}
