package com.stock.analyzer.batch.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.stock.analyzer.batch.application.BatchAnalysisRepository;
import com.stock.analyzer.batch.domain.BatchAnalysisItemState;
import com.stock.analyzer.batch.domain.BatchAnalysisState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostgresBatchAnalysisRepository implements BatchAnalysisRepository {

	private final JdbcClient jdbcClient;

	public PostgresBatchAnalysisRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	@Transactional
	public UUID create(List<String> symbols) {
		UUID batchId = UUID.randomUUID();
		jdbcClient.sql("""
				INSERT INTO analyzer.batch_analysis (id, status, total_items)
				VALUES (:id, 'QUEUED', :total)
				""").param("id", batchId).param("total", symbols.size()).update();
		for (String symbol : symbols) {
			jdbcClient.sql("""
					INSERT INTO analyzer.batch_analysis_item (id, batch_analysis_id, symbol, status)
					VALUES (:id, :batchId, :symbol, 'QUEUED')
					""").param("id", UUID.randomUUID()).param("batchId", batchId).param("symbol", symbol).update();
		}
		return batchId;
	}

	@Override
	public void markRunning(UUID batchAnalysisId) {
		jdbcClient.sql("UPDATE analyzer.batch_analysis SET status = 'RUNNING' WHERE id = :id")
				.param("id", batchAnalysisId).update();
	}

	@Override
	public void linkAnalysis(UUID batchAnalysisId, String symbol, UUID analysisRunId) {
		jdbcClient.sql("""
				UPDATE analyzer.batch_analysis_item
				SET status = 'RUNNING', analysis_run_id = :runId
				WHERE batch_analysis_id = :batchId AND symbol = :symbol
				""").param("runId", analysisRunId).param("batchId", batchAnalysisId).param("symbol", symbol).update();
	}

	@Override
	public void completeItem(UUID batchAnalysisId, String symbol) {
		jdbcClient.sql("""
				UPDATE analyzer.batch_analysis_item
				SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP
				WHERE batch_analysis_id = :batchId AND symbol = :symbol
				""").param("batchId", batchAnalysisId).param("symbol", symbol).update();
	}

	@Override
	public void failItem(UUID batchAnalysisId, String symbol, String errorCode, String errorMessage) {
		jdbcClient.sql("""
				UPDATE analyzer.batch_analysis_item
				SET status = 'FAILED', error_code = :errorCode, error_message = :errorMessage,
				    completed_at = CURRENT_TIMESTAMP
				WHERE batch_analysis_id = :batchId AND symbol = :symbol
				""").param("batchId", batchAnalysisId).param("symbol", symbol)
				.param("errorCode", errorCode).param("errorMessage", errorMessage).update();
	}

	@Override
	public void refreshSummary(UUID batchAnalysisId, boolean finished) {
		jdbcClient.sql("""
				UPDATE analyzer.batch_analysis b SET
				    completed_items = counts.completed,
				    successful_items = counts.succeeded,
				    failed_items = counts.failed,
				    status = CASE WHEN :finished THEN
				        CASE WHEN counts.failed = 0 THEN 'COMPLETED'
				             WHEN counts.succeeded = 0 THEN 'FAILED' ELSE 'PARTIAL_SUCCESS' END
				        ELSE 'RUNNING' END,
				    completed_at = CASE WHEN :finished THEN CURRENT_TIMESTAMP ELSE NULL END
				FROM (SELECT COUNT(*) FILTER (WHERE status IN ('COMPLETED', 'FAILED')) AS completed,
				             COUNT(*) FILTER (WHERE status = 'COMPLETED') AS succeeded,
				             COUNT(*) FILTER (WHERE status = 'FAILED') AS failed
				      FROM analyzer.batch_analysis_item WHERE batch_analysis_id = :id) counts
				WHERE b.id = :id
				""").param("id", batchAnalysisId).param("finished", finished).update();
	}

	@Override
	public Optional<BatchAnalysisState> find(UUID batchAnalysisId) {
		return jdbcClient.sql("""
				SELECT id, status, total_items, completed_items, successful_items, failed_items,
				       created_at, completed_at
				FROM analyzer.batch_analysis WHERE id = :id
				""").param("id", batchAnalysisId).query(this::mapBatch).optional();
	}

	@Override
	public List<BatchAnalysisItemState> findItems(UUID batchAnalysisId) {
		return jdbcClient.sql("""
				SELECT id, symbol, status, analysis_run_id, error_code, error_message, created_at, completed_at
				FROM analyzer.batch_analysis_item WHERE batch_analysis_id = :id ORDER BY created_at, symbol
				""").param("id", batchAnalysisId).query(this::mapItem).list();
	}

	private BatchAnalysisState mapBatch(ResultSet rs, int row) throws SQLException {
		return new BatchAnalysisState(rs.getObject("id", UUID.class), rs.getString("status"),
				rs.getInt("total_items"), rs.getInt("completed_items"), rs.getInt("successful_items"),
				rs.getInt("failed_items"), instant(rs, "created_at"), instant(rs, "completed_at"));
	}

	private BatchAnalysisItemState mapItem(ResultSet rs, int row) throws SQLException {
		return new BatchAnalysisItemState(rs.getObject("id", UUID.class), rs.getString("symbol"),
				rs.getString("status"), rs.getObject("analysis_run_id", UUID.class), rs.getString("error_code"),
				rs.getString("error_message"), instant(rs, "created_at"), instant(rs, "completed_at"));
	}

	private Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
