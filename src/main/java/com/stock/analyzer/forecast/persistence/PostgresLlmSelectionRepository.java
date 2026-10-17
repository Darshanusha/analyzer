package com.stock.analyzer.forecast.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.stock.analyzer.forecast.application.LlmSelectionRepository;
import com.stock.analyzer.forecast.domain.LlmSelectionCall;
import com.stock.analyzer.forecast.domain.LlmSelectionCandidateInput;
import com.stock.analyzer.forecast.domain.LlmSelectionCandidateResult;
import com.stock.analyzer.forecast.domain.LlmSelectionOutput;
import com.stock.analyzer.forecast.domain.LlmSelectionState;
import com.stock.analyzer.forecast.domain.LlmSelectionWebResearch;
import com.stock.analyzer.forecast.domain.LlmWebSource;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PostgresLlmSelectionRepository implements LlmSelectionRepository {

	private static final String SCORE_INTERPRETATION =
			"UNCALIBRATED_COMPARATIVE_SELECTION_SCORE_0_TO_100_NOT_A_PROBABILITY_OR_TRADE_RECOMMENDATION";
	private final JdbcClient jdbc;
	private final ObjectMapper mapper;

	public PostgresLlmSelectionRepository(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	@Override
	public UUID create(UUID batchAnalysisId, int horizon, int candidateLimit, int selectionLimit,
			String model, String promptVersion) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO analyzer.llm_selection_run (
				 id, batch_analysis_id, status, horizon_trading_sessions, candidate_limit,
				 selection_limit, model, prompt_version, trace_id)
				VALUES (:id,:batchId,'QUEUED',:horizon,:candidateLimit,:selectionLimit,:model,:prompt,:trace)
				""").param("id", id).param("batchId", batchAnalysisId).param("horizon", horizon)
				.param("candidateLimit", candidateLimit).param("selectionLimit", selectionLimit)
				.param("model", model).param("prompt", promptVersion).param("trace", MDC.get("trace_id")).update();
		return id;
	}

	@Override
	public void markRunning(UUID selectionId) {
		jdbc.sql("UPDATE analyzer.llm_selection_run SET status='RUNNING', started_at=CURRENT_TIMESTAMP WHERE id=:id")
				.param("id", selectionId).update();
	}

	@Override
	public List<LlmSelectionCandidateInput> loadCandidates(UUID batchAnalysisId, int horizon, int limit) {
		return jdbc.sql("""
				SELECT f.id AS forecast_id, f.analysis_run_id, f.instrument_id,
				 ROW_NUMBER() OVER (ORDER BY f.up_score DESC, f.symbol) AS original_rank,
				 f.symbol, f.stock_name, instrument.sector, f.cutoff_date, f.target_session,
				 f.information_cutoff, f.up_score, f.confidence, f.thesis,
				 run.data_quality_status, f.model, f.prompt_version, f.input_snapshot, f.response_payload
				FROM analyzer.batch_analysis_item item
				JOIN analyzer.llm_forecast f ON f.analysis_run_id=item.analysis_run_id
				JOIN analyzer.analysis_run run ON run.id=f.analysis_run_id
				JOIN analyzer.instrument instrument ON instrument.id=f.instrument_id
				WHERE item.batch_analysis_id=:batchId AND item.status='COMPLETED'
				 AND f.status='AVAILABLE' AND f.up_score IS NOT NULL
				 AND f.horizon_trading_sessions=:horizon
				ORDER BY f.up_score DESC, f.symbol LIMIT :limit
				""").param("batchId", batchAnalysisId).param("horizon", horizon).param("limit", limit)
				.query((rs, row) -> new LlmSelectionCandidateInput(
						rs.getObject("forecast_id", UUID.class), rs.getObject("analysis_run_id", UUID.class),
						rs.getObject("instrument_id", UUID.class), rs.getInt("original_rank"), rs.getString("symbol"),
						rs.getString("stock_name"), rs.getString("sector"), rs.getDate("cutoff_date").toLocalDate(),
						rs.getDate("target_session").toLocalDate(), instant(rs, "information_cutoff"),
						rs.getInt("up_score"), rs.getString("confidence"), rs.getString("thesis"),
						rs.getString("data_quality_status"), rs.getString("model"), rs.getString("prompt_version"),
						map(rs.getString("input_snapshot")), map(rs.getString("response_payload")))).list();
	}

	@Override
	@Transactional
	public void complete(UUID selectionId, Map<String, Object> inputSnapshot, String inputSha256,
			LlmSelectionCall call, List<LlmSelectionCandidateResult> candidates, long durationMs) {
		jdbc.sql("""
				UPDATE analyzer.llm_selection_run SET status='COMPLETED', candidate_count=:candidateCount,
				 selected_count=:selectedCount, model=:model, input_snapshot=CAST(:input AS jsonb),
				 input_sha256=:hash, response_payload=CAST(:response AS jsonb), input_tokens=:inputTokens,
				 output_tokens=:outputTokens, total_tokens=:totalTokens, duration_ms=:duration,
				 completed_at=CURRENT_TIMESTAMP WHERE id=:id
				""").param("id", selectionId).param("candidateCount", candidates.size())
				.param("selectedCount", candidates.stream().filter(LlmSelectionCandidateResult::selected).count())
				.param("model", call.model()).param("input", json(inputSnapshot)).param("hash", inputSha256)
				.param("response", json(call.output())).param("inputTokens", call.inputTokens())
				.param("outputTokens", call.outputTokens()).param("totalTokens", call.totalTokens())
				.param("duration", durationMs).update();
		for (LlmSelectionCandidateResult candidate : candidates) saveCandidate(selectionId, candidate);
		saveInteractions(selectionId, call);
		saveWebResearch(selectionId, call.webResearch());
	}

	private void saveCandidate(UUID selectionId, LlmSelectionCandidateResult candidate) {
		jdbc.sql("""
				INSERT INTO analyzer.llm_selection_candidate (
				 id, selection_run_id, llm_forecast_id, symbol, original_rank, original_up_score,
				 original_confidence, selected, selected_rank, selection_score, confidence,
				 comparative_thesis, bullish_evidence, bearish_evidence, comparative_advantages,
				 invalidation_risks, data_limitations, exclusion_reason)
				VALUES (:id,:selectionId,:forecastId,:symbol,:originalRank,:originalScore,:originalConfidence,
				 :selected,:selectedRank,:selectionScore,:confidence,:thesis,CAST(:bullish AS jsonb),
				 CAST(:bearish AS jsonb),CAST(:advantages AS jsonb),CAST(:risks AS jsonb),
				 CAST(:limitations AS jsonb),:exclusionReason)
				""").param("id", UUID.randomUUID()).param("selectionId", selectionId)
				.param("forecastId", candidate.forecastId()).param("symbol", candidate.symbol())
				.param("originalRank", candidate.originalRank()).param("originalScore", candidate.originalUpScore())
				.param("originalConfidence", candidate.originalConfidence()).param("selected", candidate.selected())
				.param("selectedRank", candidate.selectedRank()).param("selectionScore", candidate.selectionScore())
				.param("confidence", candidate.confidence()).param("thesis", candidate.comparativeThesis())
				.param("bullish", json(candidate.bullishEvidence())).param("bearish", json(candidate.bearishEvidence()))
				.param("advantages", json(candidate.comparativeAdvantages()))
				.param("risks", json(candidate.invalidationRisks())).param("limitations", json(candidate.dataLimitations()))
				.param("exclusionReason", candidate.exclusionReason()).update();
	}

	private void saveInteractions(UUID selectionId, LlmSelectionCall call) {
		for (var interaction : call.interactions()) {
			UUID interactionId = UUID.randomUUID();
			jdbc.sql("""
					INSERT INTO analyzer.llm_selection_interaction (
					 id, selection_run_id, provider, model, response_id, round_number, status,
					 input_tokens, output_tokens, total_tokens, duration_ms, tool_call_count,
					 web_search_count, error_code, error_message)
					VALUES (:id,:selectionId,'OPENAI',:model,:responseId,:round,:status,:input,:output,
					:total,:duration,:tools,:webSearches,:errorCode,:errorMessage)
					""").param("id", interactionId).param("selectionId", selectionId)
					.param("model", interaction.model()).param("responseId", interaction.responseId())
					.param("round", interaction.roundNumber()).param("status", interaction.status())
					.param("input", interaction.inputTokens()).param("output", interaction.outputTokens())
					.param("total", interaction.totalTokens()).param("duration", interaction.durationMs())
					.param("tools", interaction.toolCallCount()).param("errorCode", interaction.errorCode())
					.param("webSearches", interaction.webSearchCount())
					.param("errorMessage", interaction.errorMessage()).update();
			for (var tool : interaction.toolCalls()) jdbc.sql("""
					INSERT INTO analyzer.llm_selection_tool_call (
					 id, selection_interaction_id, selection_run_id, call_id, tool_name, status,
					 tool_type, duration_ms, result_count, arguments_sha256, error_code, error_message)
				VALUES (:id,:interactionId,:selectionId,:callId,:name,:status,:type,:duration,:count,
					 :hash,:errorCode,:errorMessage)
					""").param("id", UUID.randomUUID()).param("interactionId", interactionId)
					.param("selectionId", selectionId).param("callId", tool.callId()).param("name", tool.toolName())
					.param("type", tool.toolType())
					.param("status", tool.status()).param("duration", tool.durationMs())
					.param("count", tool.resultCount()).param("hash", tool.argumentsSha256())
					.param("errorCode", tool.errorCode()).param("errorMessage", tool.errorMessage()).update();
		}
	}

	private void saveWebResearch(UUID selectionId, List<LlmSelectionWebResearch> research) {
		for (var item : research) jdbc.sql("""
				INSERT INTO analyzer.llm_selection_web_research (
				 id, selection_run_id, symbol, information_cutoff, status, response_id, model,
				 summary, sources, input_tokens, output_tokens, total_tokens, duration_ms,
				 web_search_count, error_code, error_message)
				VALUES (:id,:selectionId,:symbol,:cutoff,:status,:responseId,:model,:summary,
				 CAST(:sources AS jsonb),:input,:output,:total,:duration,:searches,:errorCode,:errorMessage)
				""").param("id", UUID.randomUUID()).param("selectionId", selectionId).param("symbol", item.symbol())
				.param("cutoff", java.time.OffsetDateTime.ofInstant(
						item.informationCutoff(), java.time.ZoneOffset.UTC))
				.param("status", item.status())
				.param("responseId", item.responseId()).param("model", item.model()).param("summary", item.summary())
				.param("sources", json(item.sources())).param("input", item.inputTokens())
				.param("output", item.outputTokens()).param("total", item.totalTokens())
				.param("duration", item.durationMs()).param("searches", item.webSearchCount())
				.param("errorCode", item.errorCode()).param("errorMessage", item.errorMessage()).update();
	}

	@Override
	public void fail(UUID selectionId, String errorCode, String errorMessage, long durationMs) {
		jdbc.sql("""
				UPDATE analyzer.llm_selection_run SET status='FAILED', error_code=:code,
				 error_message=:message, duration_ms=:duration, completed_at=CURRENT_TIMESTAMP WHERE id=:id
				""").param("id", selectionId).param("code", errorCode).param("message", errorMessage)
				.param("duration", durationMs).update();
	}

	@Override
	public Optional<LlmSelectionState> find(UUID selectionId) {
		return jdbc.sql("SELECT * FROM analyzer.llm_selection_run WHERE id=:id").param("id", selectionId)
				.query(this::mapState).optional().map(this::withCandidates);
	}

	@Override
	public Optional<LlmSelectionState> findReusable(UUID batchAnalysisId, int horizon, int candidateLimit,
			int selectionLimit, String model, String promptVersion) {
		return jdbc.sql("""
				SELECT * FROM analyzer.llm_selection_run
				WHERE batch_analysis_id=:batchId AND horizon_trading_sessions=:horizon
				 AND candidate_limit=:candidateLimit AND selection_limit=:selectionLimit
				 AND model=:model AND prompt_version=:prompt AND status='COMPLETED'
				ORDER BY completed_at DESC LIMIT 1
				""").param("batchId", batchAnalysisId).param("horizon", horizon)
				.param("candidateLimit", candidateLimit).param("selectionLimit", selectionLimit)
				.param("model", model).param("prompt", promptVersion).query(this::mapState).optional()
				.map(this::withCandidates);
	}

	private LlmSelectionState withCandidates(LlmSelectionState state) {
		List<LlmSelectionCandidateResult> candidates = jdbc.sql("""
				SELECT * FROM analyzer.llm_selection_candidate WHERE selection_run_id=:id
				ORDER BY selected DESC, selected_rank NULLS LAST, original_rank
				""").param("id", state.selectionId()).query(this::mapCandidate).list();
		LlmSelectionOutput output = output(state.selectionId());
		List<LlmSelectionWebResearch> research = webResearch(state.selectionId());
		return new LlmSelectionState(state.selectionId(), state.batchAnalysisId(), state.status(),
				state.horizonTradingSessions(), state.candidateLimit(), state.selectionLimit(), state.candidateCount(),
				state.selectedCount(), state.scoreInterpretation(), state.model(), state.promptVersion(),
				state.inputTokens(), state.outputTokens(), state.totalTokens(), state.durationMs(), state.errorCode(),
				state.errorMessage(), state.reusedExistingSelection(), state.createdAt(), state.completedAt(), candidates,
				output.portfolioLevelRisks(), output.overallLimitations(), research);
	}

	private List<LlmSelectionWebResearch> webResearch(UUID selectionId) {
		return jdbc.sql("""
				SELECT * FROM analyzer.llm_selection_web_research
				WHERE selection_run_id=:id ORDER BY created_at, symbol
				""").param("id", selectionId).query((rs, row) -> new LlmSelectionWebResearch(
				rs.getString("symbol"), instant(rs, "information_cutoff"), rs.getString("status"),
				rs.getString("response_id"), rs.getString("model"), rs.getString("summary"),
				sources(rs.getString("sources")), longOrNull(rs, "input_tokens"),
				longOrNull(rs, "output_tokens"), longOrNull(rs, "total_tokens"), rs.getLong("duration_ms"),
				rs.getInt("web_search_count"), rs.getString("error_code"), rs.getString("error_message"))).list();
	}

	private LlmSelectionOutput output(UUID selectionId) {
		String value = jdbc.sql("SELECT response_payload::text FROM analyzer.llm_selection_run WHERE id=:id")
				.param("id", selectionId).query(String.class).optional().orElse(null);
		if (value == null) return new LlmSelectionOutput(List.of(), List.of(), List.of(), List.of());
		try { return mapper.readValue(value, LlmSelectionOutput.class); }
		catch (JacksonException exception) { throw new IllegalStateException("Stored selection output could not be read", exception); }
	}

	private LlmSelectionState mapState(ResultSet rs, int row) throws SQLException {
		return new LlmSelectionState(rs.getObject("id", UUID.class), rs.getObject("batch_analysis_id", UUID.class),
				rs.getString("status"), rs.getInt("horizon_trading_sessions"), rs.getInt("candidate_limit"),
				rs.getInt("selection_limit"), rs.getInt("candidate_count"), rs.getInt("selected_count"),
				SCORE_INTERPRETATION, rs.getString("model"), rs.getString("prompt_version"),
				longOrNull(rs, "input_tokens"), longOrNull(rs, "output_tokens"), longOrNull(rs, "total_tokens"),
				longOrNull(rs, "duration_ms"), rs.getString("error_code"), rs.getString("error_message"), false,
				instant(rs, "created_at"), instant(rs, "completed_at"), List.of(), List.of(), List.of(), List.of());
	}

	private LlmSelectionCandidateResult mapCandidate(ResultSet rs, int row) throws SQLException {
		return new LlmSelectionCandidateResult(rs.getObject("llm_forecast_id", UUID.class), rs.getString("symbol"),
				rs.getInt("original_rank"), rs.getInt("original_up_score"), rs.getString("original_confidence"),
				rs.getBoolean("selected"), integerOrNull(rs, "selected_rank"), integerOrNull(rs, "selection_score"),
				rs.getString("confidence"), rs.getString("comparative_thesis"), list(rs.getString("bullish_evidence")),
				list(rs.getString("bearish_evidence")), list(rs.getString("comparative_advantages")),
				list(rs.getString("invalidation_risks")), list(rs.getString("data_limitations")),
				rs.getString("exclusion_reason"));
	}

	private Map<String, Object> map(String value) {
		if (value == null || "null".equals(value)) return Map.of();
		try { return mapper.readValue(value, new TypeReference<>() { }); }
		catch (JacksonException exception) { throw new IllegalStateException("Stored selection input could not be read", exception); }
	}

	private List<String> list(String value) {
		if (value == null) return List.of();
		try { return mapper.readValue(value, new TypeReference<>() { }); }
		catch (JacksonException exception) { throw new IllegalStateException("Stored selection list could not be read", exception); }
	}

	private List<LlmWebSource> sources(String value) {
		if (value == null) return List.of();
		try { return mapper.readValue(value, new TypeReference<>() { }); }
		catch (JacksonException exception) { throw new IllegalStateException("Stored selection sources could not be read", exception); }
	}

	private String json(Object value) {
		try { return mapper.writeValueAsString(value); }
		catch (JacksonException exception) { throw new IllegalStateException("Selection data could not be serialized", exception); }
	}

	private Integer integerOrNull(ResultSet rs, String column) throws SQLException {
		Object value = rs.getObject(column); return value == null ? null : ((Number) value).intValue();
	}

	private Long longOrNull(ResultSet rs, String column) throws SQLException {
		Object value = rs.getObject(column); return value == null ? null : ((Number) value).longValue();
	}

	private Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class); return value == null ? null : value.toInstant();
	}
}
