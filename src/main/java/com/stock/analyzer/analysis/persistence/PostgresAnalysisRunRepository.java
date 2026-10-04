package com.stock.analyzer.analysis.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.stock.analyzer.analysis.application.AnalysisRunRepository;
import com.stock.analyzer.analysis.domain.AnalysisComponentResult;
import com.stock.analyzer.analysis.domain.AnalysisReport;
import com.stock.analyzer.analysis.domain.AnalysisRunState;
import com.stock.analyzer.analysis.domain.NarrativeGeneration;
import com.stock.analyzer.instrument.domain.Instrument;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PostgresAnalysisRunRepository implements AnalysisRunRepository {

	private static final String RULE_SET_VERSION = "phase1-v1";
	private static final String PROMPT_VERSION = "phase1-narrative-v1";

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public PostgresAnalysisRunRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public UUID create(Instrument instrument, String requestedSymbol) {
		UUID id = UUID.randomUUID();
		jdbcClient.sql("""
				INSERT INTO analyzer.analysis_run (
				    id, instrument_id, requested_symbol, status, rule_set_version,
				    prompt_version, probability_status, trace_id)
				VALUES (:id, :instrumentId, :symbol, 'QUEUED', :ruleSet, :prompt,
				        'MODEL_NOT_AVAILABLE', :traceId)
				""").param("id", id).param("instrumentId", instrument.id()).param("symbol", requestedSymbol)
				.param("ruleSet", RULE_SET_VERSION).param("prompt", PROMPT_VERSION)
				.param("traceId", MDC.get("trace_id")).update();
		return id;
	}

	@Override
	public void markRunning(UUID analysisRunId) {
		jdbcClient.sql("""
				UPDATE analyzer.analysis_run
				SET status = 'RUNNING', started_at = CURRENT_TIMESTAMP
				WHERE id = :id
				""").param("id", analysisRunId).update();
	}

	@Override
	@Transactional
	public void saveComponent(UUID analysisRunId, AnalysisComponentResult component) {
		jdbcClient.sql("""
				INSERT INTO analyzer.analysis_component_result (
				    id, analysis_run_id, component_key, component_version, status, summary,
				    metrics, reasons, evidence, limitations, duration_ms)
				VALUES (:id, :runId, :key, :version, :status, :summary, CAST(:metrics AS jsonb),
				        CAST(:reasons AS jsonb), CAST(:evidence AS jsonb), CAST(:limitations AS jsonb), :duration)
				ON CONFLICT (analysis_run_id, component_key) DO UPDATE SET
				    component_version = EXCLUDED.component_version, status = EXCLUDED.status,
				    summary = EXCLUDED.summary, metrics = EXCLUDED.metrics, reasons = EXCLUDED.reasons,
				    evidence = EXCLUDED.evidence, limitations = EXCLUDED.limitations,
				    duration_ms = EXCLUDED.duration_ms
				""").param("id", UUID.randomUUID()).param("runId", analysisRunId).param("key", component.key())
				.param("version", component.version()).param("status", component.status())
				.param("summary", component.summary()).param("metrics", json(component.metrics()))
				.param("reasons", json(component.reasons())).param("evidence", json(component.evidence()))
				.param("limitations", json(component.limitations())).param("duration", component.durationMs())
				.update();
	}

	@Override
	public void recordNarrativeInvocation(UUID analysisRunId, NarrativeGeneration generation) {
		jdbcClient.sql("""
				INSERT INTO analyzer.llm_invocation (
				    id, analysis_run_id, provider, model, prompt_version, status,
				    input_tokens, output_tokens, total_tokens, duration_ms, trace_id, error_code)
				VALUES (:id, :runId, :provider, :model, :prompt, :status, :inputTokens,
				        :outputTokens, :totalTokens, :duration, :traceId, :errorCode)
				""").param("id", UUID.randomUUID()).param("runId", analysisRunId)
				.param("provider", generation.provider()).param("model", generation.model())
				.param("prompt", PROMPT_VERSION).param("status", generation.status())
				.param("inputTokens", generation.inputTokens()).param("outputTokens", generation.outputTokens())
				.param("totalTokens", generation.totalTokens()).param("duration", generation.durationMs())
				.param("traceId", MDC.get("trace_id")).param("errorCode", generation.errorCode()).update();
	}

	@Override
	public void complete(UUID analysisRunId, AnalysisReport report, LocalDate priceCutoff,
			String dataQualityStatus, NarrativeGeneration narrative, long durationMs) {
		jdbcClient.sql("""
				UPDATE analyzer.analysis_run SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP,
				    analysis_cutoff = :cutoff, price_cutoff_date = :priceCutoff,
				    llm_provider = :provider, llm_model = :model, data_quality_status = :quality,
				    probability_up = :probability, probability_status = :probabilityStatus,
				    final_output = CAST(:output AS jsonb), duration_ms = :duration
				WHERE id = :id
				""").param("id", analysisRunId)
				.param("cutoff", java.time.OffsetDateTime.ofInstant(report.analysisCutoff(), java.time.ZoneOffset.UTC))
				.param("priceCutoff", priceCutoff).param("provider", narrative.provider())
				.param("model", narrative.model()).param("quality", dataQualityStatus)
				.param("probability", report.probabilityUp()).param("probabilityStatus", report.probabilityStatus())
				.param("output", json(report)).param("duration", durationMs).update();
	}

	@Override
	public void fail(UUID analysisRunId, String errorCode, String errorMessage, long durationMs) {
		jdbcClient.sql("""
				UPDATE analyzer.analysis_run SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP,
				    error_code = :errorCode, error_message = :errorMessage, duration_ms = :duration
				WHERE id = :id
				""").param("id", analysisRunId).param("errorCode", errorCode)
				.param("errorMessage", errorMessage).param("duration", durationMs).update();
	}

	@Override
	public Optional<AnalysisRunState> find(UUID analysisRunId) {
		return jdbcClient.sql("""
				SELECT id, requested_symbol, status, requested_at, started_at, completed_at,
				       final_output::text, error_code, error_message
				FROM analyzer.analysis_run WHERE id = :id
				""").param("id", analysisRunId).query(this::mapState).optional();
	}

	@Override
	public List<AnalysisRunState> findBySymbol(String symbol, int limit) {
		return jdbcClient.sql("""
				SELECT id, requested_symbol, status, requested_at, started_at, completed_at,
				       final_output::text, error_code, error_message
				FROM analyzer.analysis_run
				WHERE requested_symbol = :symbol
				ORDER BY requested_at DESC LIMIT :limit
				""").param("symbol", symbol).param("limit", limit).query(this::mapState).list();
	}

	private AnalysisRunState mapState(ResultSet rs, int row) throws SQLException {
		String output = rs.getString("final_output");
		AnalysisReport report = output == null ? null : readReport(output);
		return new AnalysisRunState(rs.getObject("id", UUID.class), rs.getString("status"),
				rs.getString("requested_symbol"), rs.getObject("requested_at", java.time.OffsetDateTime.class).toInstant(),
				instant(rs, "started_at"), instant(rs, "completed_at"), report,
				rs.getString("error_code"), rs.getString("error_message"));
	}

	private java.time.Instant instant(ResultSet rs, String column) throws SQLException {
		java.time.OffsetDateTime value = rs.getObject(column, java.time.OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private AnalysisReport readReport(String json) {
		try {
			return objectMapper.readValue(json, AnalysisReport.class);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Stored analysis output could not be read", exception);
		}
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Analysis output could not be serialized", exception);
		}
	}
}
