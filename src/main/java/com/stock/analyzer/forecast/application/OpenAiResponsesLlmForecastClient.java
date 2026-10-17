package com.stock.analyzer.forecast.application;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.stock.analyzer.configuration.LlmForecastProperties;
import com.stock.analyzer.forecast.domain.AiInteractionAudit;
import com.stock.analyzer.forecast.domain.AiToolCallAudit;
import com.stock.analyzer.forecast.domain.LlmForecastCall;
import com.stock.analyzer.forecast.domain.LlmHorizonForecastOutput;
import com.stock.analyzer.forecast.domain.LlmMultiHorizonOutput;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Responses API client with bounded, stateless function and hosted-web tool calling. */
@Component
public class OpenAiResponsesLlmForecastClient implements LlmForecastClient {
	private static final int HARD_MAX_DATABASE_CALLS = 5;
	private static final int HARD_MAX_WEB_SEARCH_CALLS = 5;
	private static final List<String> DB_TOOLS = List.of("get_available_stock_data", "get_price_history",
			"get_fundamental_history", "get_market_context", "get_sector_context");
	private static final String RECENT_NEWS_TOOL = "get_recent_news";
	private final LlmForecastProperties properties;
	private final LlmDatabaseToolExecutor tools;
	private final ObjectMapper mapper;
	private final MeterRegistry meters;
	private final ObservationRegistry observations;
	private final String model;
	private final String instructions;
	private final RestClient rest;

	public OpenAiResponsesLlmForecastClient(LlmForecastProperties properties, LlmDatabaseToolExecutor tools,
			ObjectMapper mapper, MeterRegistry meters, ObservationRegistry observations,
			Environment environment, LlmForecastPromptCatalog prompts) {
		this.properties = properties;
		this.tools = tools;
		this.mapper = mapper;
		this.meters = meters;
		this.observations = observations;
		this.model = environment.getProperty("spring.ai.openai.chat.options.model", "gpt-6.1-sol");
		this.instructions = prompts.load(properties.getPromptVersion());
		var web = properties.getWebResearch();
		HttpClient http = HttpClient.newBuilder().connectTimeout(web.getConnectTimeout()).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
		factory.setReadTimeout(web.getReadTimeout());
		this.rest = RestClient.builder().baseUrl(web.getBaseUrl().replaceAll("/+$", ""))
				.requestFactory(factory).build();
	}

	@Override public boolean isAvailable() { return text(properties.getWebResearch().getApiKey()) && text(model); }
	@Override public String configuredModel() { return model; }

	@Override
	public LlmForecastCall forecast(String evidenceJson) {
		long forecastStarted = System.nanoTime();
		if (!isAvailable()) throw new IllegalStateException("OpenAI API key is not configured");
		try {
			JsonNode evidence = mapper.readTree(evidenceJson);
			UUID instrumentId = UUID.fromString(evidence.path("instrumentId").asText());
			LocalDate cutoff = LocalDate.parse(evidence.path("cutoffDate").asText());
			List<Object> conversation = new ArrayList<>();
			conversation.add(Map.of("role", "user", "content", evidenceJson));
			List<AiInteractionAudit> interactions = new ArrayList<>();
			List<LlmHorizonForecastOutput> forecasts = List.of();
			long allIn = 0, allOut = 0, allTotal = 0;
			int dbCalls = 0;
			int webCallsTotal = 0;
			int maxDatabaseCalls = maxDatabaseCalls();
			int maxWebSearchCalls = maxWebSearchCalls();
			int maxRounds = Math.min(12, Math.max(2, maxDatabaseCalls + maxWebSearchCalls + 2));
			String responseModel = model;

			for (int round = 1; round <= maxRounds; round++) {
				long started = System.nanoTime();
				Observation responseObservation = Observation.start("stock.analysis.ai.response", observations)
						.lowCardinalityKeyValue("model", model).lowCardinalityKeyValue("round", Integer.toString(round));
				JsonNode response;
				try { response = invoke(conversation, webCallsTotal, maxWebSearchCalls); }
				catch (RuntimeException ex) { responseObservation.error(ex); throw ex; }
				finally { responseObservation.stop(); }
				long duration = elapsed(started);
				responseModel = response.path("model").asText(model);
				JsonNode usage = response.path("usage");
				Long in = number(usage, "input_tokens"), out = number(usage, "output_tokens"), total = number(usage, "total_tokens");
				allIn += zero(in); allOut += zero(out); allTotal += zero(total);
				List<AiToolCallAudit> audits = new ArrayList<>();
				List<Object> outputs = new ArrayList<>();
				int webCalls = 0;
				for (JsonNode item : response.path("output")) {
					conversation.add(mapper.readValue(item.toString(), Object.class));
					String type = item.path("type").asText();
					if ("web_search_call".equals(type)) {
						webCallsTotal++; webCalls++;
						audits.add(new AiToolCallAudit(item.path("id").asText(), "WEB_SEARCH", "web_search",
								item.path("status").asText("completed").toUpperCase(), duration,
								item.path("action").path("sources").size(), null, null, null));
					}
					if (!"function_call".equals(type)) continue;
					String name = item.path("name").asText(), callId = item.path("call_id").asText();
					String arguments = item.path("arguments").asText("{}");
					long toolStarted = System.nanoTime();
					Observation toolObservation = Observation.start("stock.analysis.ai.tool", observations)
							.lowCardinalityKeyValue("type", "database").lowCardinalityKeyValue("tool", name);
					String output, status = "COMPLETED", errorCode = null, errorMessage = null;
					Integer rowCount = null;
					try {
						if (dbCalls >= maxDatabaseCalls) throw new IllegalStateException("Database tool budget exhausted");
						if (!availableDatabaseTools().contains(name)) throw new IllegalArgumentException("Unsupported tool: " + name);
						dbCalls++;
						int limit = mapper.readTree(arguments).path("limit").asInt(20);
						var result = tools.execute(name, instrumentId, cutoff, limit);
						output = result.json(); rowCount = result.resultCount();
					}
					catch (RuntimeException ex) {
						toolObservation.error(ex);
						status = "FAILED"; errorCode = ex.getClass().getSimpleName(); errorMessage = safe(ex.getMessage());
						output = json(Map.of("error", errorCode, "message", errorMessage));
					}
					finally { toolObservation.stop(); }
					long toolDuration = elapsed(toolStarted);
					outputs.add(Map.of("type", "function_call_output", "call_id", callId, "output", output));
					audits.add(new AiToolCallAudit(callId, "DATABASE", name, status, toolDuration, rowCount,
							sha256(arguments), errorCode, errorMessage));
					recordTool(name, status, toolDuration);
				}
				interactions.add(new AiInteractionAudit(response.path("id").asText(null), responseModel, round,
						"COMPLETED", in, out, total, duration, outputs.size(), webCalls, null, null, audits));
				recordRound(responseModel, in, out, total, duration, webCalls);
				if (outputs.isEmpty()) {
					String finalText = outputText(response);
					if (!finalText.isBlank()) forecasts = mapper.readValue(finalText, LlmMultiHorizonOutput.class).forecasts();
					if (!forecasts.isEmpty()) break;
				}
				conversation.addAll(outputs);
			}
			if (forecasts.isEmpty()) throw new IllegalStateException("OpenAI returned no structured multi-horizon forecast");
			LlmHorizonForecastOutput primary = forecasts.stream().filter(f -> f.horizonTradingSessions() == 1)
					.findFirst().orElseThrow(() -> new IllegalStateException("One-session forecast is missing"));
			return new LlmForecastCall(primary.asForecastOutput(), responseModel, allIn, allOut, allTotal,
					forecasts, interactions);
		}
		catch (JacksonException ex) {
			recordFailure(ex, forecastStarted);
			throw new IllegalStateException("OpenAI Responses payload could not be parsed", ex);
		}
		catch (RuntimeException ex) {
			recordFailure(ex, forecastStarted);
			throw ex;
		}
	}

	private JsonNode invoke(List<Object> input, int webCallsUsed, int maxWebSearchCalls) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model); body.put("instructions", instructions); body.put("input", input);
		body.put("tools", toolDefinitions(webCallsUsed, maxWebSearchCalls)); body.put("tool_choice", "auto");
		int remainingWebCalls = Math.max(0, maxWebSearchCalls - webCallsUsed);
		if (remainingWebCalls > 0) body.put("max_tool_calls", remainingWebCalls);
		body.put("text", Map.of("format", Map.of("type", "json_schema", "name", "stock_forecasts",
				"strict", true, "schema", outputSchema())));
		body.put("include", List.of("web_search_call.action.sources", "reasoning.encrypted_content"));
		body.put("store", false);
		String value = rest.post().uri("/responses")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getWebResearch().getApiKey())
				.contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
		if (!text(value)) throw new IllegalStateException("OpenAI returned an empty response");
		try { return mapper.readTree(value); } catch (JacksonException ex) { throw new IllegalStateException("Invalid OpenAI JSON", ex); }
	}

	private List<Map<String, Object>> toolDefinitions(int webCallsUsed, int maxWebSearchCalls) {
		List<Map<String, Object>> result = new ArrayList<>();
		for (String name : availableDatabaseTools()) result.add(Map.of("type", "function", "name", name,
				"description", description(name), "strict", true,
				"parameters", Map.of("type", "object", "properties", Map.of("limit", Map.of("type", "integer",
						"minimum", 1, "maximum", 120)), "required", List.of("limit"), "additionalProperties", false)));
		if (webCallsUsed < maxWebSearchCalls) result.add(Map.of("type", "web_search", "filters",
				Map.of("allowed_domains", properties.getWebResearch().getAllowedDomains()), "external_web_access", true));
		return result;
	}

	List<String> availableDatabaseTools() {
		if (!properties.isRecentNewsToolEnabled()) return DB_TOOLS;
		List<String> result = new ArrayList<>(DB_TOOLS);
		result.add(RECENT_NEWS_TOOL);
		return List.copyOf(result);
	}

	int maxDatabaseCalls() {
		return Math.max(0, Math.min(properties.getMaxDatabaseToolCalls(), HARD_MAX_DATABASE_CALLS));
	}

	int maxWebSearchCalls() {
		if (!properties.getWebResearch().isEnabled()) return 0;
		int configured = Math.max(0, Math.min(properties.getWebResearch().getMaxRequestsPerForecast(),
				HARD_MAX_WEB_SEARCH_CALLS));
		return properties.isRecentNewsToolEnabled() ? Math.min(configured, 1) : configured;
	}

	private Map<String, Object> outputSchema() {
		Map<String, Object> strings = Map.of("type", "array", "items", Map.of("type", "string"));
		Map<String, Object> item = new LinkedHashMap<>(); item.put("type", "object");
		item.put("properties", Map.of("horizonTradingSessions", Map.of("type", "integer", "enum", List.of(1, 5)),
				"upScore", Map.of("type", "integer", "minimum", 0, "maximum", 100), "confidence", Map.of("type", "string",
						"enum", List.of("LOW", "MEDIUM", "HIGH")), "thesis", Map.of("type", "string"),
				"bullishEvidence", strings, "bearishEvidence", strings, "risks", strings, "dataLimitations", strings));
		item.put("required", List.of("horizonTradingSessions", "upScore", "confidence", "thesis", "bullishEvidence",
				"bearishEvidence", "risks", "dataLimitations")); item.put("additionalProperties", false);
		return Map.of("type", "object", "properties", Map.of("forecasts", Map.of("type", "array", "items", item,
				"minItems", 2, "maxItems", 2)), "required", List.of("forecasts"), "additionalProperties", false);
	}

	private String description(String name) { return switch (name) {
		case "get_available_stock_data" -> "Count available point-in-time price, fundamental, and news rows.";
		case "get_price_history" -> "Get bounded daily OHLCV history through the cutoff.";
		case "get_fundamental_history" -> "Get bounded historical fundamentals available by the cutoff.";
		case "get_recent_news" -> "Get bounded stored news available by the cutoff.";
		case "get_market_context" -> "Get bounded SPY closes through the cutoff.";
		case "get_sector_context" -> "Get bounded sector ETF closes through the cutoff.";
		default -> name;
	}; }

	private String outputText(JsonNode root) { StringBuilder b = new StringBuilder(); for (JsonNode i : root.path("output"))
		for (JsonNode p : i.path("content")) if ("output_text".equals(p.path("type").asText())) b.append(p.path("text").asText()); return b.toString(); }
	private void recordRound(String m, Long in, Long out, Long total, long ms, int web) {
		meters.counter("stock.analysis.ai.responses", "model", m, "status", "success").increment();
		meters.timer("stock.analysis.ai.response.duration", "model", m, "status", "success").record(ms, TimeUnit.MILLISECONDS);
		meters.counter("stock.analysis.ai.tokens", "model", m, "type", "input").increment(zero(in));
		meters.counter("stock.analysis.ai.tokens", "model", m, "type", "output").increment(zero(out));
		meters.counter("stock.analysis.ai.tokens", "model", m, "type", "total").increment(zero(total));
		meters.counter("stock.analysis.ai.tool.calls", "type", "web_search", "tool", "web_search", "status", "COMPLETED").increment(web);
	}
	private void recordTool(String name, String status, long ms) { meters.counter("stock.analysis.ai.tool.calls", "type", "database", "tool", name, "status", status).increment(); meters.timer("stock.analysis.ai.tool.duration", "type", "database", "tool", name, "status", status).record(ms, TimeUnit.MILLISECONDS); }
	private void recordFailure(RuntimeException ex, long started) { String error = ex.getClass().getSimpleName(); meters.counter("stock.analysis.ai.responses", "model", model, "status", "failure").increment(); meters.counter("stock.analysis.ai.errors", "model", model, "type", error).increment(); meters.timer("stock.analysis.ai.response.duration", "model", model, "status", "failure").record(elapsed(started), TimeUnit.MILLISECONDS); }
	private Long number(JsonNode n, String f) { return n.path(f).isNumber() ? n.path(f).asLong() : null; }
	private long zero(Long n) { return n == null ? 0 : n; }
	private long elapsed(long n) { return (System.nanoTime() - n) / 1_000_000; }
	private boolean text(String s) { return s != null && !s.isBlank(); }
	private String safe(String s) { s = text(s) ? s : "Unknown tool error"; return s.substring(0, Math.min(1000, s.length())); }
	private String json(Object v) { try { return mapper.writeValueAsString(v); } catch (JacksonException e) { throw new IllegalStateException(e); } }
	private String sha256(String s) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
