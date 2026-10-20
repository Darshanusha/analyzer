package com.stock.analyzer.agent.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Fixed read-only queries for the research agent. Arbitrary model-generated SQL is never executed. */
@Component
public class ResearchAgentToolExecutor {

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;

	public ResearchAgentToolExecutor(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	public ToolResult execute(String name, JsonNode arguments) {
		return switch (name) {
			case "get_stock_analysis" -> stockAnalysis(symbol(arguments), limit(arguments, 30));
			case "get_stock_forecasts" -> stockForecasts(symbol(arguments), limit(arguments, 20));
			case "get_batch_status" -> batchStatus(uuid(arguments, "batchAnalysisId"), limit(arguments, 200));
			case "get_batch_rankings" -> batchRankings(uuid(arguments, "batchAnalysisId"),
					horizon(arguments), limit(arguments, 50));
			case "get_selection_result" -> selectionResult(uuid(arguments, "selectionId"));
			case "get_forecast_performance" -> forecastPerformance(optionalSymbol(arguments), horizon(arguments));
			default -> throw new IllegalArgumentException("Unsupported research-agent tool: " + name);
		};
	}

	private ToolResult stockAnalysis(String symbol, int limit) {
		List<Map<String, Object>> rows = jdbc.sql("""
				SELECT run.id AS analysis_run_id, instrument.current_symbol AS symbol, instrument.name AS stock_name,
				 run.status AS analysis_status, run.analysis_cutoff, run.price_cutoff_date,
				 run.probability_up, run.probability_status, run.data_quality_status,
				 component.component_key, component.status AS component_status, component.summary,
				 component.metrics::text AS metrics, component.reasons::text AS reasons,
				 component.evidence::text AS evidence, component.limitations::text AS limitations
				FROM analyzer.analysis_run run
				JOIN analyzer.instrument instrument ON instrument.id=run.instrument_id
				LEFT JOIN analyzer.analysis_component_result component ON component.analysis_run_id=run.id
				WHERE instrument.current_symbol=:symbol
				ORDER BY run.requested_at DESC, component.component_key
				LIMIT :limit
				""").param("symbol", symbol).param("limit", Math.min(limit, 100)).query().listOfRows();
		return result("get_stock_analysis", rows);
	}

	private ToolResult stockForecasts(String symbol, int limit) {
		List<Map<String, Object>> rows = jdbc.sql("""
				SELECT id AS forecast_id, analysis_run_id, symbol, stock_name, cutoff_date,
				 target_session, horizon_trading_sessions, information_cutoff, status, direction,
				 up_score, upside_threshold_percent, downside_threshold_percent,
				 upside_touch_score, downside_touch_score, touch_score_spread,
				 confidence, thesis, response_payload::text AS evidence,
				 model, prompt_version, created_at,
				 outcome.actual_session, outcome.actual_up, outcome.correct,
				 outcome.upside_target_touched, outcome.downside_target_touched,
				 outcome.upside_first_touch_date, outcome.downside_first_touch_date,
				 outcome.touch_sequence, outcome.maximum_return_percent,
				 outcome.maximum_drawdown_percent, outcome.horizon_return_percent
				FROM analyzer.llm_forecast forecast
				LEFT JOIN analyzer.llm_forecast_outcome outcome ON outcome.llm_forecast_id=forecast.id
				WHERE forecast.symbol=:symbol
				ORDER BY forecast.created_at DESC, forecast.horizon_trading_sessions
				LIMIT :limit
				""").param("symbol", symbol).param("limit", Math.min(limit, 50)).query().listOfRows();
		return result("get_stock_forecasts", rows);
	}

	private ToolResult batchStatus(UUID batchId, int limit) {
		List<Map<String, Object>> batch = jdbc.sql("""
				SELECT * FROM analyzer.batch_analysis WHERE id=:id
				""").param("id", batchId).query().listOfRows();
		List<Map<String, Object>> items = jdbc.sql("""
				SELECT symbol, status, analysis_run_id, reused_existing_analysis,
				 error_code, error_message, completed_at
				FROM analyzer.batch_analysis_item WHERE batch_analysis_id=:id
				ORDER BY status DESC, symbol LIMIT :limit
				""").param("id", batchId).param("limit", Math.min(limit, 600)).query().listOfRows();
		return result("get_batch_status", Map.of("batch", batch, "items", items), batch.size() + items.size());
	}

	private ToolResult batchRankings(UUID batchId, int horizon, int limit) {
		List<Map<String, Object>> rows = jdbc.sql("""
				SELECT ROW_NUMBER() OVER (ORDER BY COALESCE(forecast.touch_score_spread, forecast.up_score) DESC,
				 COALESCE(forecast.upside_touch_score, forecast.up_score) DESC, item.symbol) AS ranking,
				 item.symbol, forecast.analysis_run_id, forecast.direction, forecast.up_score,
				 forecast.upside_threshold_percent, forecast.downside_threshold_percent,
				 forecast.upside_touch_score, forecast.downside_touch_score, forecast.touch_score_spread,
				 forecast.confidence, forecast.thesis, forecast.target_session,
				 run.data_quality_status, forecast.model, forecast.prompt_version,
				 outcome.actual_up, outcome.correct, outcome.upside_target_touched,
				 outcome.downside_target_touched, outcome.touch_sequence
				FROM analyzer.batch_analysis_item item
				JOIN analyzer.llm_forecast forecast ON forecast.analysis_run_id=item.analysis_run_id
				JOIN analyzer.analysis_run run ON run.id=item.analysis_run_id
				LEFT JOIN analyzer.llm_forecast_outcome outcome ON outcome.llm_forecast_id=forecast.id
				WHERE item.batch_analysis_id=:batchId AND item.status='COMPLETED'
				 AND forecast.status='AVAILABLE' AND forecast.up_score IS NOT NULL
				 AND forecast.horizon_trading_sessions=:horizon
				ORDER BY COALESCE(forecast.touch_score_spread, forecast.up_score) DESC,
				 COALESCE(forecast.upside_touch_score, forecast.up_score) DESC, item.symbol LIMIT :limit
				""").param("batchId", batchId).param("horizon", horizon)
				.param("limit", Math.min(limit, 100)).query().listOfRows();
		return result("get_batch_rankings", rows);
	}

	private ToolResult selectionResult(UUID selectionId) {
		List<Map<String, Object>> run = jdbc.sql("""
				SELECT id, batch_analysis_id, status, horizon_trading_sessions, candidate_count,
				 selected_count, model, prompt_version, input_tokens, output_tokens, total_tokens,
				 duration_ms, error_code, error_message, created_at, completed_at, objective,
				 upside_threshold_percent, downside_threshold_percent,
				 response_payload::text AS selection_output
				FROM analyzer.llm_selection_run WHERE id=:id
				""").param("id", selectionId).query().listOfRows();
		List<Map<String, Object>> candidates = jdbc.sql("""
				SELECT symbol, original_rank, original_up_score, original_upside_touch_score,
				 original_downside_touch_score, original_touch_score_spread, original_confidence, selected,
				 selected_rank, selection_score, confidence, comparative_thesis,
				 bullish_evidence::text, bearish_evidence::text, comparative_advantages::text,
				 invalidation_risks::text, data_limitations::text, exclusion_reason
				FROM analyzer.llm_selection_candidate WHERE selection_run_id=:id
				ORDER BY selected DESC, selected_rank NULLS LAST, original_rank
				""").param("id", selectionId).query().listOfRows();
		List<Map<String, Object>> research = jdbc.sql("""
				SELECT symbol, information_cutoff, status, model, summary, sources::text,
				 web_search_count, error_code, error_message
				FROM analyzer.llm_selection_web_research WHERE selection_run_id=:id
				ORDER BY symbol
				""").param("id", selectionId).query().listOfRows();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("selection", run);
		payload.put("candidates", candidates);
		payload.put("webResearch", research);
		return result("get_selection_result", payload, run.size() + candidates.size() + research.size());
	}

	private ToolResult forecastPerformance(String symbol, int horizon) {
		String normalized = symbol == null ? "" : symbol;
		List<Map<String, Object>> rows = jdbc.sql("""
				SELECT NULLIF(:symbol,'') AS requested_symbol, :horizon AS horizon_trading_sessions,
				 COUNT(*) AS scored,
				 AVG(CASE WHEN outcome.correct THEN 1.0 ELSE 0.0 END) AS accuracy,
				 AVG(forecast.up_score) AS average_up_score,
				 AVG(CASE WHEN outcome.actual_up THEN 1.0 ELSE 0.0 END) AS actual_up_rate,
				 AVG(forecast.upside_touch_score) AS average_upside_touch_score,
				 AVG(forecast.downside_touch_score) AS average_downside_touch_score,
				 AVG(CASE WHEN outcome.upside_target_touched THEN 1.0 ELSE 0.0 END) AS actual_upside_touch_rate,
				 AVG(CASE WHEN outcome.downside_target_touched THEN 1.0 ELSE 0.0 END) AS actual_downside_touch_rate
				FROM analyzer.llm_forecast forecast
				JOIN analyzer.llm_forecast_outcome outcome ON outcome.llm_forecast_id=forecast.id
				WHERE forecast.status='AVAILABLE' AND forecast.direction IN ('UP','DOWN')
				 AND forecast.horizon_trading_sessions=:horizon
				 AND (:symbol='' OR forecast.symbol=:symbol)
				""").param("symbol", normalized).param("horizon", horizon).query().listOfRows();
		return result("get_forecast_performance", rows);
	}

	private ToolResult result(String tool, List<Map<String, Object>> rows) {
		return result(tool, Map.of("rows", rows), rows.size());
	}

	private ToolResult result(String tool, Object payload, int count) {
		return new ToolResult(json(Map.of("tool", tool, "resultCount", count, "data", payload)), count);
	}

	private String symbol(JsonNode arguments) {
		String value = arguments.path("symbol").asText().strip().toUpperCase(Locale.ROOT);
		if (!value.matches("[A-Z][A-Z0-9.-]{0,14}")) throw new IllegalArgumentException("Invalid symbol");
		return value;
	}

	private String optionalSymbol(JsonNode arguments) {
		String value = arguments.path("symbol").asText("").strip().toUpperCase(Locale.ROOT);
		if (!value.isEmpty() && !value.matches("[A-Z][A-Z0-9.-]{0,14}")) {
			throw new IllegalArgumentException("Invalid symbol");
		}
		return value;
	}

	private UUID uuid(JsonNode arguments, String field) {
		try { return UUID.fromString(arguments.path(field).asText()); }
		catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid " + field); }
	}

	private int limit(JsonNode arguments, int defaultValue) {
		return Math.max(1, Math.min(arguments.path("limit").asInt(defaultValue), 600));
	}

	private int horizon(JsonNode arguments) {
		int value = arguments.path("horizonTradingSessions").asInt(5);
		if (value != 1 && value != 5) throw new IllegalArgumentException("horizonTradingSessions must be 1 or 5");
		return value;
	}

	private String json(Object value) {
		try { return mapper.writeValueAsString(value); }
		catch (JacksonException exception) { throw new IllegalStateException("Research tool result could not be serialized", exception); }
	}

	public record ToolResult(String json, int resultCount) { }
}
