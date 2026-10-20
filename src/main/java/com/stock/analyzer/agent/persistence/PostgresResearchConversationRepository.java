package com.stock.analyzer.agent.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.stock.analyzer.agent.application.ResearchConversationRepository;
import com.stock.analyzer.agent.domain.ResearchAgentResponse;
import com.stock.analyzer.agent.domain.ResearchConversation;
import com.stock.analyzer.agent.domain.ResearchMessage;
import com.stock.analyzer.agent.domain.ResearchSummaryResult;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostgresResearchConversationRepository implements ResearchConversationRepository {

	private final JdbcClient jdbc;

	public PostgresResearchConversationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public ResearchConversation create(String title) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO analyzer.agent_conversation (id, title, status)
				VALUES (:id, :title, 'ACTIVE')
				""").param("id", id).param("title", title).update();
		return find(id).orElseThrow();
	}

	@Override
	public Optional<ResearchConversation> find(UUID conversationId) {
		return jdbc.sql("""
				SELECT * FROM analyzer.agent_conversation WHERE id=:id
				""").param("id", conversationId).query(this::mapConversation).optional();
	}

	@Override
	public List<ResearchConversation> findRecent(int limit) {
		return jdbc.sql("""
				SELECT * FROM analyzer.agent_conversation
				ORDER BY updated_at DESC LIMIT :limit
				""").param("limit", Math.max(1, Math.min(limit, 100))).query(this::mapConversation).list();
	}

	@Override
	public List<ResearchMessage> findMessages(UUID conversationId, int afterSequence, int limit) {
		return jdbc.sql("""
				SELECT * FROM analyzer.agent_message
				WHERE conversation_id=:id AND sequence_number>:after
				ORDER BY sequence_number LIMIT :limit
				""").param("id", conversationId).param("after", Math.max(0, afterSequence))
				.param("limit", Math.max(1, Math.min(limit, 500))).query(this::mapMessage).list();
	}

	@Override
	@Transactional
	public ResearchMessage appendUserMessage(UUID conversationId, String content) {
		int sequence = nextSequence(conversationId);
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO analyzer.agent_message (id, conversation_id, sequence_number, role, content)
				VALUES (:id, :conversationId, :sequence, 'USER', :content)
				""").param("id", id).param("conversationId", conversationId).param("sequence", sequence)
				.param("content", content).update();
		return findMessage(id);
	}

	@Override
	@Transactional
	public ResearchMessage appendAssistantMessage(UUID conversationId, ResearchAgentResponse response) {
		int sequence = nextSequence(conversationId);
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO analyzer.agent_message (
				 id, conversation_id, sequence_number, role, content, openai_response_id, model,
				 input_tokens, output_tokens, total_tokens, duration_ms)
				VALUES (:id,:conversationId,:sequence,'ASSISTANT',:content,:responseId,:model,
				 :inputTokens,:outputTokens,:totalTokens,:duration)
				""").param("id", id).param("conversationId", conversationId).param("sequence", sequence)
				.param("content", response.answer()).param("responseId", response.responseId())
				.param("model", response.model()).param("inputTokens", response.inputTokens())
				.param("outputTokens", response.outputTokens()).param("totalTokens", response.totalTokens())
				.param("duration", response.durationMs()).update();
		for (var tool : response.toolCalls()) {
			jdbc.sql("""
					INSERT INTO analyzer.agent_tool_call (
					 id, conversation_id, assistant_message_id, call_id, tool_name, arguments, result,
					 status, duration_ms, result_count, error_code, error_message)
					VALUES (:id,:conversationId,:messageId,:callId,:toolName,CAST(:arguments AS jsonb),
					 CAST(:result AS jsonb),:status,:duration,:resultCount,:errorCode,:errorMessage)
					""").param("id", UUID.randomUUID()).param("conversationId", conversationId)
					.param("messageId", id).param("callId", tool.callId()).param("toolName", tool.toolName())
					.param("arguments", tool.argumentsJson()).param("result", tool.resultJson())
					.param("status", tool.status()).param("duration", tool.durationMs())
					.param("resultCount", tool.resultCount()).param("errorCode", tool.errorCode())
					.param("errorMessage", tool.errorMessage()).update();
		}
		return findMessage(id);
	}

	@Override
	@Transactional
	public ResearchConversation saveSummary(UUID conversationId, int fromSequence, int throughSequence,
			ResearchSummaryResult result) {
		int version = jdbc.sql("""
				UPDATE analyzer.agent_conversation SET current_summary=:summary,
				 summarized_message_count=:through, summary_version=summary_version+1,
				 updated_at=CURRENT_TIMESTAMP
				WHERE id=:id AND summarized_message_count<:through
				RETURNING summary_version
				""").param("summary", result.summary()).param("through", throughSequence)
				.param("id", conversationId).query(Integer.class).optional()
				.orElseThrow(() -> new IllegalStateException("Conversation summary was already updated"));
		jdbc.sql("""
				INSERT INTO analyzer.agent_conversation_summary (
				 id, conversation_id, summary_version, from_message_sequence, through_message_sequence,
				 summary, model, input_tokens, output_tokens, total_tokens, duration_ms)
				VALUES (:id,:conversationId,:version,:fromSequence,:throughSequence,:summary,:model,
				 :inputTokens,:outputTokens,:totalTokens,:duration)
				""").param("id", UUID.randomUUID()).param("conversationId", conversationId)
				.param("version", version).param("fromSequence", fromSequence)
				.param("throughSequence", throughSequence).param("summary", result.summary())
				.param("model", result.model()).param("inputTokens", result.inputTokens())
				.param("outputTokens", result.outputTokens()).param("totalTokens", result.totalTokens())
				.param("duration", result.durationMs()).update();
		return find(conversationId).orElseThrow();
	}

	private int nextSequence(UUID conversationId) {
		return jdbc.sql("""
				UPDATE analyzer.agent_conversation SET message_count=message_count+1,
				 updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='ACTIVE'
				RETURNING message_count
				""").param("id", conversationId).query(Integer.class).optional()
				.orElseThrow(() -> new IllegalStateException("Conversation is not active"));
	}

	private ResearchMessage findMessage(UUID id) {
		return jdbc.sql("SELECT * FROM analyzer.agent_message WHERE id=:id").param("id", id)
				.query(this::mapMessage).single();
	}

	private ResearchConversation mapConversation(ResultSet rs, int row) throws SQLException {
		return new ResearchConversation(rs.getObject("id", UUID.class), rs.getString("title"),
				rs.getString("status"), rs.getString("current_summary"), rs.getInt("message_count"),
				rs.getInt("summarized_message_count"), rs.getInt("summary_version"),
				instant(rs, "created_at"), instant(rs, "updated_at"));
	}

	private ResearchMessage mapMessage(ResultSet rs, int row) throws SQLException {
		return new ResearchMessage(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
				rs.getInt("sequence_number"), rs.getString("role"), rs.getString("content"),
				rs.getString("openai_response_id"), rs.getString("model"), longOrNull(rs, "input_tokens"),
				longOrNull(rs, "output_tokens"), longOrNull(rs, "total_tokens"), longOrNull(rs, "duration_ms"),
				instant(rs, "created_at"));
	}

	private Long longOrNull(ResultSet rs, String column) throws SQLException {
		Object value = rs.getObject(column);
		return value == null ? null : ((Number) value).longValue();
	}

	private Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
