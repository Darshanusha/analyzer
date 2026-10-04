package com.stock.analyzer.forecast.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.stock.analyzer.forecast.application.LlmInformationGapRepository;
import com.stock.analyzer.forecast.domain.LlmInformationGapSummary;
import com.stock.analyzer.forecast.domain.LlmInformationRequestAudit;
import com.stock.analyzer.forecast.domain.LlmWebResearchResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PostgresLlmInformationGapRepository implements LlmInformationGapRepository {

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public PostgresLlmInformationGapRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	@Override
	@Transactional
	public void record(LlmInformationRequestAudit audit) {
		String initialWorkflowStatus = "WEB_RESOLVED".equals(audit.resolutionStatus()) ? "AVAILABLE" : "NEW";
		jdbcClient.sql("""
				INSERT INTO analyzer.llm_information_gap (
				    fingerprint, category, requested_data, reason, importance, preferred_source_type,
				    workflow_status, occurrence_count, first_requested_at, last_requested_at)
				VALUES (:fingerprint, :category, :requestedData, :reason, :importance, :sourceType,
				        :workflowStatus, 1, :createdAt, :createdAt)
				ON CONFLICT (fingerprint) DO UPDATE SET
				    occurrence_count = analyzer.llm_information_gap.occurrence_count + 1,
				    last_requested_at = EXCLUDED.last_requested_at,
				    reason = EXCLUDED.reason,
				    importance = EXCLUDED.importance,
				    workflow_status = CASE
				        WHEN analyzer.llm_information_gap.workflow_status = 'NEW'
				             AND EXCLUDED.workflow_status = 'AVAILABLE' THEN 'AVAILABLE'
				        ELSE analyzer.llm_information_gap.workflow_status
				    END
				""").param("fingerprint", audit.fingerprint()).param("category", audit.request().category())
				.param("requestedData", audit.request().dataRequested()).param("reason", audit.request().reason())
				.param("importance", audit.request().importance())
				.param("sourceType", audit.request().preferredSourceType())
				.param("workflowStatus", initialWorkflowStatus)
				.param("createdAt", OffsetDateTime.ofInstant(audit.createdAt(), ZoneOffset.UTC)).update();

		LlmWebResearchResult web = audit.webResearch();
		jdbcClient.sql("""
				INSERT INTO analyzer.llm_information_request (
				    id, llm_forecast_id, gap_fingerprint, resolution_status, search_query,
				    web_provider, web_model, web_summary, web_sources, input_tokens, output_tokens,
				    total_tokens, duration_ms, error_code, error_message, raw_request, created_at)
				VALUES (:id, :forecastId, :fingerprint, :resolutionStatus, :searchQuery,
				        :webProvider, :webModel, :webSummary, CAST(:webSources AS jsonb), :inputTokens,
				        :outputTokens, :totalTokens, :durationMs, :errorCode, :errorMessage,
				        CAST(:rawRequest AS jsonb), :createdAt)
				""").param("id", audit.id()).param("forecastId", audit.forecastId())
				.param("fingerprint", audit.fingerprint()).param("resolutionStatus", audit.resolutionStatus())
				.param("searchQuery", audit.request().searchQuery())
				.param("webProvider", web == null ? null : "OPENAI_WEB_SEARCH")
				.param("webModel", web == null ? null : web.model())
				.param("webSummary", web == null ? null : web.summary())
				.param("webSources", json(web == null ? List.of() : web.sources()))
				.param("inputTokens", web == null ? null : web.inputTokens())
				.param("outputTokens", web == null ? null : web.outputTokens())
				.param("totalTokens", web == null ? null : web.totalTokens())
				.param("durationMs", web == null ? 0L : web.durationMs())
				.param("errorCode", web == null ? null : web.errorCode())
				.param("errorMessage", web == null ? null : web.errorMessage())
				.param("rawRequest", json(audit.request()))
				.param("createdAt", OffsetDateTime.ofInstant(audit.createdAt(), ZoneOffset.UTC)).update();
	}

	@Override
	public List<LlmInformationGapSummary> find(String status, int limit) {
		return jdbcClient.sql("""
				SELECT fingerprint, category, requested_data, reason, importance, preferred_source_type,
				       workflow_status, occurrence_count, first_requested_at, last_requested_at,
				       resolution_notes
				FROM analyzer.llm_information_gap
				WHERE CAST(:status AS VARCHAR) IS NULL OR workflow_status = :status
				ORDER BY occurrence_count DESC, last_requested_at DESC
				LIMIT :limit
				""").param("status", status).param("limit", limit).query(this::mapGap).list();
	}

	@Override
	public boolean update(String fingerprint, String status, String resolutionNotes) {
		return jdbcClient.sql("""
				UPDATE analyzer.llm_information_gap
				SET workflow_status = :status, resolution_notes = :notes,
				    resolved_at = CASE WHEN :status IN ('AVAILABLE', 'REJECTED')
				                       THEN CURRENT_TIMESTAMP ELSE NULL END
				WHERE fingerprint = :fingerprint
				""").param("status", status).param("notes", resolutionNotes)
				.param("fingerprint", fingerprint).update() == 1;
	}

	private LlmInformationGapSummary mapGap(ResultSet rs, int row) throws SQLException {
		return new LlmInformationGapSummary(rs.getString("fingerprint"), rs.getString("category"),
				rs.getString("requested_data"), rs.getString("reason"), rs.getString("importance"),
				rs.getString("preferred_source_type"), rs.getString("workflow_status"),
				rs.getLong("occurrence_count"), instant(rs, "first_requested_at"),
				instant(rs, "last_requested_at"), rs.getString("resolution_notes"));
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
			throw new IllegalStateException("LLM information request could not be serialized", exception);
		}
	}
}
