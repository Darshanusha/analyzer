package com.stock.analyzer.forecast.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stock.analyzer.forecast.application.LlmForecastRepository;
import com.stock.analyzer.forecast.domain.BatchLlmRankingItem;
import com.stock.analyzer.forecast.domain.AiInteractionAudit;
import com.stock.analyzer.forecast.domain.LlmForecastOutcome;
import com.stock.analyzer.forecast.domain.LlmForecastOutput;
import com.stock.analyzer.forecast.domain.LlmForecastPerformanceSummary;
import com.stock.analyzer.forecast.domain.LlmForecastResult;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PostgresLlmForecastRepository implements LlmForecastRepository {

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public PostgresLlmForecastRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	@Override
	@Transactional
	public void save(LlmForecastResult result, Map<String, Object> inputSnapshot) {
		jdbcClient.sql("""
				INSERT INTO analyzer.llm_forecast (
				    id, analysis_run_id, instrument_id, symbol, stock_name,
				    cutoff_date, target_session, horizon_trading_sessions, information_cutoff, status,
				    direction, up_score, confidence, thesis, score_interpretation,
				    provider, model, prompt_version, input_snapshot, input_sha256, response_payload,
				    input_tokens, output_tokens, total_tokens, duration_ms, error_code, error_message,
				    trace_id, created_at)
				SELECT :id, :runId, :instrumentId, instrument.current_symbol, instrument.name,
				        :cutoff, :target, :horizon, :informationCutoff, :status,
				        :direction, :score, :confidence, :thesis, :scoreInterpretation,
				        :provider, :model, :promptVersion, CAST(:input AS jsonb), :inputHash,
				        CAST(:response AS jsonb), :inputTokens, :outputTokens, :totalTokens,
				        :duration, :errorCode, :errorMessage, :traceId, :createdAt
				FROM analyzer.instrument instrument
				WHERE instrument.id = :instrumentId
				ON CONFLICT (analysis_run_id, horizon_trading_sessions) DO UPDATE SET
				    symbol = EXCLUDED.symbol, stock_name = EXCLUDED.stock_name,
				    status = EXCLUDED.status, direction = EXCLUDED.direction, up_score = EXCLUDED.up_score,
				    confidence = EXCLUDED.confidence, thesis = EXCLUDED.thesis,
				    score_interpretation = EXCLUDED.score_interpretation, model = EXCLUDED.model,
				    prompt_version = EXCLUDED.prompt_version, input_snapshot = EXCLUDED.input_snapshot,
				    input_sha256 = EXCLUDED.input_sha256, response_payload = EXCLUDED.response_payload,
				    input_tokens = EXCLUDED.input_tokens, output_tokens = EXCLUDED.output_tokens,
				    total_tokens = EXCLUDED.total_tokens, duration_ms = EXCLUDED.duration_ms,
				    error_code = EXCLUDED.error_code, error_message = EXCLUDED.error_message
				""").param("id", result.forecastId()).param("runId", result.analysisRunId())
				.param("instrumentId", result.instrumentId()).param("cutoff", result.cutoffDate())
				.param("target", result.targetSession()).param("horizon", result.horizonTradingSessions())
				.param("informationCutoff", result.informationCutoff() == null ? null
						: OffsetDateTime.ofInstant(result.informationCutoff(), java.time.ZoneOffset.UTC))
				.param("status", result.status())
				.param("direction", result.direction()).param("score", result.upScore())
				.param("confidence", result.confidence()).param("thesis", result.thesis())
				.param("scoreInterpretation", result.scoreInterpretation()).param("provider", result.provider())
				.param("model", result.model()).param("promptVersion", result.promptVersion())
				.param("input", json(inputSnapshot)).param("inputHash", result.inputSha256())
				.param("response", responseJson(result)).param("inputTokens", result.inputTokens())
				.param("outputTokens", result.outputTokens()).param("totalTokens", result.totalTokens())
				.param("duration", result.durationMs()).param("errorCode", result.errorCode())
				.param("errorMessage", result.errorMessage()).param("traceId", MDC.get("trace_id"))
				.param("createdAt", OffsetDateTime.ofInstant(result.createdAt(), java.time.ZoneOffset.UTC)).update();

	}

	@Override
	@Transactional
	public void saveInteractions(UUID analysisRunId, List<AiInteractionAudit> interactions) {
		for (AiInteractionAudit interaction : interactions) {
			UUID interactionId = UUID.randomUUID();
			jdbcClient.sql("""
					INSERT INTO analyzer.ai_interaction (id, analysis_run_id, provider, model, response_id,
					 round_number, status, input_tokens, output_tokens, total_tokens, duration_ms,
					 tool_call_count, web_search_count, trace_id, error_code, error_message)
					VALUES (:id,:runId,'OPENAI',:model,:responseId,:round,:status,:input,:output,:total,
					 :duration,:tools,:web,:trace,:errorCode,:errorMessage)
					""").param("id", interactionId).param("runId", analysisRunId).param("model", interaction.model())
					.param("responseId", interaction.responseId()).param("round", interaction.roundNumber())
					.param("status", interaction.status()).param("input", interaction.inputTokens())
					.param("output", interaction.outputTokens()).param("total", interaction.totalTokens())
					.param("duration", interaction.durationMs()).param("tools", interaction.toolCallCount())
					.param("web", interaction.webSearchCount()).param("trace", MDC.get("trace_id"))
					.param("errorCode", interaction.errorCode()).param("errorMessage", interaction.errorMessage()).update();
			for (var tool : interaction.toolCalls()) jdbcClient.sql("""
					INSERT INTO analyzer.ai_tool_call (id, ai_interaction_id, analysis_run_id, call_id, tool_type,
					 tool_name, status, duration_ms, result_count, arguments_sha256, error_code, error_message)
					VALUES (:id,:interactionId,:runId,:callId,:type,:name,:status,:duration,:count,:hash,:errorCode,:errorMessage)
					""").param("id", UUID.randomUUID()).param("interactionId", interactionId).param("runId", analysisRunId)
					.param("callId", tool.callId()).param("type", tool.toolType()).param("name", tool.toolName())
					.param("status", tool.status()).param("duration", tool.durationMs()).param("count", tool.resultCount())
					.param("hash", tool.argumentsSha256()).param("errorCode", tool.errorCode())
					.param("errorMessage", tool.errorMessage()).update();
		}
	}

	@Override
	public int resolveOutcomes(UUID instrumentId) {
		return jdbcClient.sql("""
				INSERT INTO analyzer.llm_forecast_outcome (
				    id, llm_forecast_id, actual_session, cutoff_adjusted_close, actual_adjusted_close,
				    actual_up, predicted_up, correct)
				SELECT gen_random_uuid(), f.id, next_bar.price_date,
				       COALESCE(cutoff_bar.close_fully_adjusted, cutoff_bar.close_split_adjusted),
				       COALESCE(next_bar.close_fully_adjusted, next_bar.close_split_adjusted),
				       COALESCE(next_bar.close_fully_adjusted, next_bar.close_split_adjusted)
				           > COALESCE(cutoff_bar.close_fully_adjusted, cutoff_bar.close_split_adjusted),
				       CASE f.direction WHEN 'UP' THEN TRUE WHEN 'DOWN' THEN FALSE ELSE NULL END,
				       CASE WHEN f.direction NOT IN ('UP', 'DOWN') THEN NULL ELSE
				           (f.direction = 'UP') =
				           (COALESCE(next_bar.close_fully_adjusted, next_bar.close_split_adjusted)
				            > COALESCE(cutoff_bar.close_fully_adjusted, cutoff_bar.close_split_adjusted)) END
				FROM analyzer.llm_forecast f
				JOIN analyzer.daily_price_bar cutoff_bar
				  ON cutoff_bar.instrument_id = f.instrument_id AND cutoff_bar.price_date = f.cutoff_date
				JOIN LATERAL (
				    SELECT b.* FROM analyzer.daily_price_bar b
				    WHERE b.instrument_id = f.instrument_id AND b.price_date > f.cutoff_date
				    ORDER BY b.price_date OFFSET (f.horizon_trading_sessions - 1) LIMIT 1
				) next_bar ON TRUE
				LEFT JOIN analyzer.llm_forecast_outcome existing ON existing.llm_forecast_id = f.id
				WHERE f.instrument_id = :instrumentId AND existing.id IS NULL AND f.status = 'AVAILABLE'
				""").param("instrumentId", instrumentId).update();
	}

	@Override
	public List<LlmForecastResult> findBySymbol(String symbol, int limit) {
		return jdbcClient.sql("""
				SELECT f.*, o.actual_session, o.cutoff_adjusted_close,
				       o.actual_adjusted_close, o.actual_up, o.predicted_up, o.correct, o.resolved_at
				FROM analyzer.llm_forecast f
				LEFT JOIN analyzer.llm_forecast_outcome o ON o.llm_forecast_id = f.id
				WHERE f.symbol = :symbol
				ORDER BY f.created_at DESC LIMIT :limit
				""").param("symbol", symbol).param("limit", limit).query(this::mapForecast).list();
	}

	@Override
	public List<LlmForecastResult> findByAnalysisRun(UUID analysisRunId) {
		return jdbcClient.sql("""
				SELECT f.*, o.actual_session, o.cutoff_adjusted_close, o.actual_adjusted_close,
				       o.actual_up, o.predicted_up, o.correct, o.resolved_at
				FROM analyzer.llm_forecast f
				LEFT JOIN analyzer.llm_forecast_outcome o ON o.llm_forecast_id=f.id
				WHERE f.analysis_run_id=:runId ORDER BY f.horizon_trading_sessions
				""").param("runId", analysisRunId).query(this::mapForecast).list();
	}

	@Override
	public List<BatchLlmRankingItem> findBatchRankings(UUID batchAnalysisId, int horizonTradingSessions) {
		return jdbcClient.sql("""
				SELECT ROW_NUMBER() OVER (ORDER BY f.up_score DESC, item.symbol) AS ranking,
				       item.symbol, f.analysis_run_id, f.direction, f.up_score, f.confidence,
				       f.thesis, f.target_session, run.data_quality_status, f.model, f.prompt_version,
				       outcome.actual_up, outcome.correct
				FROM analyzer.batch_analysis_item item
				JOIN analyzer.llm_forecast f ON f.analysis_run_id = item.analysis_run_id
				JOIN analyzer.analysis_run run ON run.id = item.analysis_run_id
				LEFT JOIN analyzer.llm_forecast_outcome outcome ON outcome.llm_forecast_id = f.id
				WHERE item.batch_analysis_id = :batchId AND item.status = 'COMPLETED'
				  AND f.status = 'AVAILABLE' AND f.up_score IS NOT NULL
				  AND f.horizon_trading_sessions = :horizon
				ORDER BY f.up_score DESC, item.symbol
				""").param("batchId", batchAnalysisId).param("horizon", horizonTradingSessions).query((rs, row) -> new BatchLlmRankingItem(
				rs.getInt("ranking"), rs.getString("symbol"), rs.getObject("analysis_run_id", UUID.class),
				rs.getString("direction"), rs.getInt("up_score"), rs.getString("confidence"),
				rs.getString("thesis"), rs.getDate("target_session").toLocalDate(),
				rs.getString("data_quality_status"), rs.getString("model"), rs.getString("prompt_version"),
				(Boolean) rs.getObject("actual_up"), (Boolean) rs.getObject("correct"))).list();
	}

	@Override
	public LlmForecastPerformanceSummary performanceSummary() {
		return jdbcClient.sql("""
				SELECT COUNT(*) AS scored,
				       AVG(CASE WHEN o.correct THEN 1.0 ELSE 0.0 END) AS accuracy,
				       AVG(f.up_score) AS average_score,
				       AVG(CASE WHEN o.actual_up THEN 1.0 ELSE 0.0 END) AS actual_up_rate
				FROM analyzer.llm_forecast f
				JOIN analyzer.llm_forecast_outcome o ON o.llm_forecast_id = f.id
				WHERE f.status = 'AVAILABLE' AND f.direction IN ('UP', 'DOWN')
				""").query((rs, row) -> new LlmForecastPerformanceSummary(rs.getLong("scored"),
				doubleOrNull(rs, "accuracy"), doubleOrNull(rs, "average_score"),
				doubleOrNull(rs, "actual_up_rate"))).single();
	}

	private LlmForecastResult mapForecast(ResultSet rs, int row) throws SQLException {
		LlmForecastOutput output = readOutput(rs.getString("response_payload"));
		LlmForecastOutcome outcome = rs.getDate("actual_session") == null ? null : new LlmForecastOutcome(
				rs.getDate("actual_session").toLocalDate(), rs.getBigDecimal("cutoff_adjusted_close"),
				rs.getBigDecimal("actual_adjusted_close"), rs.getBoolean("actual_up"),
				(Boolean) rs.getObject("predicted_up"), (Boolean) rs.getObject("correct"), instant(rs, "resolved_at"));
		return new LlmForecastResult(rs.getObject("id", UUID.class), rs.getObject("analysis_run_id", UUID.class),
				rs.getObject("instrument_id", UUID.class), rs.getString("symbol"),
				rs.getDate("cutoff_date").toLocalDate(), rs.getDate("target_session").toLocalDate(),
				rs.getString("status"), rs.getString("direction"), integerOrNull(rs, "up_score"),
				rs.getString("confidence"), rs.getString("thesis"),
				output == null ? List.of() : output.bullishEvidence(),
				output == null ? List.of() : output.bearishEvidence(),
				output == null ? List.of() : output.risks(),
				output == null ? List.of() : output.dataLimitations(),
				rs.getString("score_interpretation"), rs.getString("provider"), rs.getString("model"),
				rs.getString("prompt_version"), rs.getString("input_sha256"), longOrNull(rs, "input_tokens"),
				longOrNull(rs, "output_tokens"), longOrNull(rs, "total_tokens"), rs.getLong("duration_ms"),
				rs.getString("error_code"), rs.getString("error_message"), instant(rs, "created_at"), outcome,
				rs.getInt("horizon_trading_sessions"), instant(rs, "information_cutoff"));
	}

	private String responseJson(LlmForecastResult result) {
		if (result.upScore() == null) return "null";
		return json(new LlmForecastOutput(result.upScore(), result.confidence(), result.thesis(),
				result.bullishEvidence(), result.bearishEvidence(), result.risks(), result.dataLimitations()));
	}

	private LlmForecastOutput readOutput(String value) {
		if (value == null || "null".equals(value)) return null;
		try {
			return objectMapper.readValue(value, LlmForecastOutput.class);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Stored LLM forecast output could not be read", exception);
		}
	}

	private Integer integerOrNull(ResultSet rs, String column) throws SQLException {
		Object value = rs.getObject(column);
		return value == null ? null : ((Number) value).intValue();
	}

	private Long longOrNull(ResultSet rs, String column) throws SQLException {
		Object value = rs.getObject(column);
		return value == null ? null : ((Number) value).longValue();
	}

	private Double doubleOrNull(ResultSet rs, String column) throws SQLException {
		Object value = rs.getObject(column);
		return value == null ? null : ((Number) value).doubleValue();
	}

	private Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("LLM forecast data could not be serialized", exception);
		}
	}
}
