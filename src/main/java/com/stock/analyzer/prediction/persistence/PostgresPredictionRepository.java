package com.stock.analyzer.prediction.persistence;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.stock.analyzer.prediction.application.PredictionRepository;
import com.stock.analyzer.prediction.domain.FeatureSnapshot;
import com.stock.analyzer.prediction.domain.LoadedModelInference;
import com.stock.analyzer.prediction.domain.ModelInference;
import com.stock.analyzer.prediction.domain.PredictionOutcome;
import com.stock.analyzer.prediction.domain.PredictionPerformanceSummary;
import com.stock.analyzer.prediction.domain.PredictionResult;
import com.stock.analyzer.prediction.domain.ProbabilityModelArtifact;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PostgresPredictionRepository implements PredictionRepository {

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public PostgresPredictionRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public UUID saveFeatureSnapshot(FeatureSnapshot snapshot) {
		return jdbcClient.sql("""
				INSERT INTO analyzer.feature_snapshot (
				    id, analysis_run_id, instrument_id, cutoff_date, target_session,
				    feature_set_version, completeness_status, features, missing_features)
				VALUES (:id, :runId, :instrumentId, :cutoff, :target, :version, :status,
				        CAST(:features AS jsonb), CAST(:missing AS jsonb))
				ON CONFLICT (instrument_id, cutoff_date, feature_set_version) DO UPDATE SET
				    analysis_run_id = EXCLUDED.analysis_run_id, target_session = EXCLUDED.target_session,
				    completeness_status = EXCLUDED.completeness_status, features = EXCLUDED.features,
				    missing_features = EXCLUDED.missing_features
				RETURNING id
				""").param("id", snapshot.id()).param("runId", snapshot.analysisRunId())
				.param("instrumentId", snapshot.instrumentId()).param("cutoff", snapshot.cutoffDate())
				.param("target", snapshot.targetSession()).param("version", snapshot.featureSetVersion())
				.param("status", snapshot.completenessStatus()).param("features", json(snapshot.features()))
				.param("missing", json(snapshot.missingFeatures())).query(UUID.class).single();
	}

	@Override
	@Transactional
	public UUID registerChampion(ProbabilityModelArtifact artifact, Path artifactPath, String sha256) {
		UUID modelId = jdbcClient.sql("""
				INSERT INTO analyzer.model_definition (
				    id, model_version, feature_set_version, calibration_version, algorithm,
				    target_definition, status, trained_at, training_start_date, training_end_date,
				    training_rows, metadata)
				VALUES (:newId, :version, :featureVersion, :calibrationVersion, :algorithm,
				        :target, 'CANDIDATE', :trainedAt, :startDate, :endDate, :rows, CAST(:metadata AS jsonb))
				ON CONFLICT (model_version) DO UPDATE SET metadata = EXCLUDED.metadata
				RETURNING id
				""").param("newId", UUID.randomUUID()).param("version", artifact.modelVersion())
				.param("featureVersion", artifact.featureSetVersion())
				.param("calibrationVersion", artifact.calibrationVersion()).param("algorithm", artifact.algorithm())
				.param("target", "next completed U.S. session adjusted close > cutoff adjusted close")
				.param("trainedAt", offset(artifact.trainedAt())).param("startDate", artifact.trainingStartDate())
				.param("endDate", artifact.trainingEndDate()).param("rows", artifact.trainingRows())
				.param("metadata", json(artifact.metrics())).query(UUID.class).single();
		jdbcClient.sql("UPDATE analyzer.model_definition SET status = 'RETIRED' WHERE status = 'CHAMPION' AND id <> :id")
				.param("id", modelId).update();
		jdbcClient.sql("UPDATE analyzer.model_definition SET status = 'CHAMPION' WHERE id = :id")
				.param("id", modelId).update();
		jdbcClient.sql("""
				INSERT INTO analyzer.model_artifact (
				    id, model_definition_id, artifact_uri, sha256, artifact_format, schema_version)
				VALUES (:id, :modelId, :uri, :sha, 'JSON_LOGISTIC_REGRESSION', :schemaVersion)
				ON CONFLICT (sha256) DO NOTHING
				""").param("id", UUID.randomUUID()).param("modelId", modelId)
				.param("uri", artifactPath.toString()).param("sha", sha256)
				.param("schemaVersion", artifact.schemaVersion()).update();
		if (jdbcClient.sql("SELECT COUNT(*) FROM analyzer.model_evaluation WHERE model_definition_id = :id")
				.param("id", modelId).query(Integer.class).single() == 0) {
			jdbcClient.sql("""
					INSERT INTO analyzer.model_evaluation (
					    id, model_definition_id, evaluation_type, period_start, period_end,
					    sample_count, metrics, baselines, calibration_bands)
					VALUES (:id, :modelId, 'WALK_FORWARD', :startDate, :endDate, :rows,
					        CAST(:metrics AS jsonb), '{}'::jsonb, '[]'::jsonb)
					""").param("id", UUID.randomUUID()).param("modelId", modelId)
					.param("startDate", artifact.trainingStartDate()).param("endDate", artifact.trainingEndDate())
					.param("rows", artifact.trainingRows()).param("metrics", json(artifact.metrics())).update();
		}
		return modelId;
	}

	@Override
	public PredictionResult savePrediction(String symbol, FeatureSnapshot snapshot, UUID featureSnapshotId,
			LoadedModelInference loaded, UUID modelDefinitionId, String dataQualityStatus) {
		ModelInference inference = loaded.inference();
		UUID predictionId = UUID.randomUUID();
		return jdbcClient.sql("""
				INSERT INTO analyzer.prediction (
				    id, analysis_run_id, instrument_id, feature_snapshot_id, model_definition_id,
				    cutoff_date, target_session, probability_up, status, model_version,
				    calibration_version, data_quality_status, trace_id)
				VALUES (:id, :runId, :instrumentId, :snapshotId, :modelId, :cutoff, :target,
				        :probability, :status, :modelVersion, :calibrationVersion, :quality, :traceId)
				ON CONFLICT (analysis_run_id) DO UPDATE SET
				    feature_snapshot_id = EXCLUDED.feature_snapshot_id,
				    model_definition_id = EXCLUDED.model_definition_id,
				    probability_up = EXCLUDED.probability_up, status = EXCLUDED.status,
				    model_version = EXCLUDED.model_version,
				    calibration_version = EXCLUDED.calibration_version,
				    data_quality_status = EXCLUDED.data_quality_status
				RETURNING id, created_at
				""").param("id", predictionId).param("runId", snapshot.analysisRunId())
				.param("instrumentId", snapshot.instrumentId()).param("snapshotId", featureSnapshotId)
				.param("modelId", modelDefinitionId).param("cutoff", snapshot.cutoffDate())
				.param("target", snapshot.targetSession()).param("probability", inference.probabilityUp())
				.param("status", inference.status()).param("modelVersion", inference.modelVersion())
				.param("calibrationVersion", inference.calibrationVersion()).param("quality", dataQualityStatus)
				.param("traceId", MDC.get("trace_id"))
				.query((rs, row) -> new PredictionResult(rs.getObject("id", UUID.class), snapshot.analysisRunId(), symbol,
						snapshot.cutoffDate(), snapshot.targetSession(), inference.probabilityUp(), inference.status(),
						inference.modelVersion(), inference.calibrationVersion(), snapshot.featureSetVersion(),
						dataQualityStatus, instant(rs, "created_at"), null)).single();
	}

	@Override
	public List<PredictionResult> findBySymbol(String symbol, int limit) {
		return jdbcClient.sql("""
				SELECT p.id, p.analysis_run_id, i.current_symbol, p.cutoff_date, p.target_session,
				       p.probability_up, p.status, p.model_version, p.calibration_version,
				       fs.feature_set_version, p.data_quality_status, p.created_at,
				       po.actual_session, po.cutoff_adjusted_close, po.actual_adjusted_close,
				       po.actual_up, po.predicted_up, po.correct, po.brier_component, po.resolved_at
				FROM analyzer.prediction p
				JOIN analyzer.instrument i ON i.id = p.instrument_id
				JOIN analyzer.feature_snapshot fs ON fs.id = p.feature_snapshot_id
				LEFT JOIN analyzer.prediction_outcome po ON po.prediction_id = p.id
				WHERE i.current_symbol = :symbol
				ORDER BY p.created_at DESC LIMIT :limit
				""").param("symbol", symbol).param("limit", limit).query(this::mapPrediction).list();
	}

	@Override
	public int resolveOutcomes(UUID instrumentId) {
		return jdbcClient.sql("""
				INSERT INTO analyzer.prediction_outcome (
				    id, prediction_id, actual_session, cutoff_adjusted_close, actual_adjusted_close,
				    actual_up, predicted_up, correct, brier_component)
				SELECT gen_random_uuid(), p.id, next_bar.price_date,
				       COALESCE(cutoff_bar.close_fully_adjusted, cutoff_bar.close_split_adjusted),
				       COALESCE(next_bar.close_fully_adjusted, next_bar.close_split_adjusted),
				       COALESCE(next_bar.close_fully_adjusted, next_bar.close_split_adjusted)
				           > COALESCE(cutoff_bar.close_fully_adjusted, cutoff_bar.close_split_adjusted),
				       CASE WHEN p.probability_up IS NULL THEN NULL ELSE p.probability_up >= 0.5 END,
				       CASE WHEN p.probability_up IS NULL THEN NULL ELSE
				           (p.probability_up >= 0.5) =
				           (COALESCE(next_bar.close_fully_adjusted, next_bar.close_split_adjusted)
				            > COALESCE(cutoff_bar.close_fully_adjusted, cutoff_bar.close_split_adjusted)) END,
				       CASE WHEN p.probability_up IS NULL THEN NULL ELSE POWER(p.probability_up -
				           CASE WHEN COALESCE(next_bar.close_fully_adjusted, next_bar.close_split_adjusted)
				                > COALESCE(cutoff_bar.close_fully_adjusted, cutoff_bar.close_split_adjusted)
				                THEN 1 ELSE 0 END, 2) END
				FROM analyzer.prediction p
				JOIN analyzer.daily_price_bar cutoff_bar
				  ON cutoff_bar.instrument_id = p.instrument_id AND cutoff_bar.price_date = p.cutoff_date
				JOIN LATERAL (
				    SELECT b.* FROM analyzer.daily_price_bar b
				    WHERE b.instrument_id = p.instrument_id AND b.price_date > p.cutoff_date
				    ORDER BY b.price_date LIMIT 1
				) next_bar ON TRUE
				LEFT JOIN analyzer.prediction_outcome existing ON existing.prediction_id = p.id
				WHERE p.instrument_id = :instrumentId AND existing.id IS NULL
				""").param("instrumentId", instrumentId).update();
	}

	@Override
	public PredictionPerformanceSummary performanceSummary() {
		return jdbcClient.sql("""
				SELECT COUNT(*) AS scored,
				       AVG(CASE WHEN po.correct THEN 1.0 ELSE 0.0 END) AS accuracy,
				       AVG(po.brier_component) AS brier,
				       AVG(p.probability_up) AS average_probability,
				       AVG(CASE WHEN po.actual_up THEN 1.0 ELSE 0.0 END) AS actual_up_rate
				FROM analyzer.prediction p
				JOIN analyzer.prediction_outcome po ON po.prediction_id = p.id
				WHERE p.probability_up IS NOT NULL
				""").query((rs, row) -> new PredictionPerformanceSummary(rs.getLong("scored"),
				doubleOrNull(rs, "accuracy"), doubleOrNull(rs, "brier"),
				doubleOrNull(rs, "average_probability"), doubleOrNull(rs, "actual_up_rate"))).single();
	}

	private PredictionResult mapPrediction(ResultSet rs, int row) throws SQLException {
		PredictionOutcome outcome = rs.getDate("actual_session") == null ? null : new PredictionOutcome(
				rs.getDate("actual_session").toLocalDate(), rs.getBigDecimal("cutoff_adjusted_close"),
				rs.getBigDecimal("actual_adjusted_close"), rs.getBoolean("actual_up"),
				(Boolean) rs.getObject("predicted_up"), (Boolean) rs.getObject("correct"),
				rs.getObject("brier_component") == null ? null : rs.getDouble("brier_component"),
				instant(rs, "resolved_at"));
		return new PredictionResult(rs.getObject("id", UUID.class), rs.getObject("analysis_run_id", UUID.class),
				rs.getString("current_symbol"), rs.getDate("cutoff_date").toLocalDate(),
				rs.getDate("target_session").toLocalDate(),
				rs.getObject("probability_up") == null ? null : rs.getDouble("probability_up"),
				rs.getString("status"), rs.getString("model_version"), rs.getString("calibration_version"),
				rs.getString("feature_set_version"), rs.getString("data_quality_status"),
				instant(rs, "created_at"), outcome);
	}

	private Double doubleOrNull(ResultSet rs, String column) throws SQLException {
		Object value = rs.getObject(column);
		return value == null ? null : ((Number) value).doubleValue();
	}

	private Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private OffsetDateTime offset(Instant value) {
		return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Prediction data could not be serialized", exception);
		}
	}
}
