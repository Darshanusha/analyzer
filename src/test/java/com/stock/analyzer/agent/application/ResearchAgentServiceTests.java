package com.stock.analyzer.agent.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.stock.analyzer.agent.domain.ResearchAgentResponse;
import com.stock.analyzer.agent.domain.ResearchConversation;
import com.stock.analyzer.agent.domain.ResearchMessage;
import com.stock.analyzer.agent.domain.ResearchSummaryResult;
import com.stock.analyzer.configuration.ResearchAgentProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchAgentServiceTests {

	@Test
	void summarizesAfterConfiguredMessageThresholdWithoutDeletingMessages() {
		UUID conversationId = UUID.randomUUID();
		ResearchConversationRepository repository = mock(ResearchConversationRepository.class);
		OpenAiResearchAgentClient client = mock(OpenAiResearchAgentClient.class);
		ResearchAgentProperties properties = new ResearchAgentProperties();
		properties.setEnabled(true);
		properties.setApiKey("test-key");
		properties.setSummaryThresholdMessages(2);
		ResearchAgentService service = new ResearchAgentService(repository, client, properties,
				new SimpleMeterRegistry());

		Instant now = Instant.parse("2026-10-18T08:00:00Z");
		var initial = conversation(conversationId, null, 0, 0, 0, now);
		var afterUser = conversation(conversationId, null, 1, 0, 0, now);
		var afterAssistant = conversation(conversationId, null, 2, 0, 0, now);
		var afterSummary = conversation(conversationId, "AAPL was discussed.", 2, 2, 1, now);
		var user = message(conversationId, 1, "USER", "What is the latest AAPL forecast?", now);
		var assistant = message(conversationId, 2, "ASSISTANT", "The latest forecast is available.", now);
		var response = new ResearchAgentResponse("resp-1", "test-model", assistant.content(),
				100L, 20L, 120L, 30L, List.of());
		var summary = new ResearchSummaryResult("resp-summary", "test-model", "AAPL was discussed.",
				50L, 10L, 60L, 15L);

		when(client.isAvailable()).thenReturn(true);
		when(repository.find(conversationId)).thenReturn(Optional.of(initial), Optional.of(afterUser),
				Optional.of(afterAssistant), Optional.of(afterSummary));
		when(repository.appendUserMessage(conversationId, user.content())).thenReturn(user);
		when(repository.findMessages(conversationId, 0, 500)).thenReturn(List.of(user), List.of(user, assistant));
		when(client.respond(null, List.of(user))).thenReturn(response);
		when(repository.appendAssistantMessage(conversationId, response)).thenReturn(assistant);
		when(client.summarize(null, List.of(user, assistant))).thenReturn(summary);
		when(repository.saveSummary(conversationId, 1, 2, summary)).thenReturn(afterSummary);

		var turn = service.send(conversationId, user.content());

		assertThat(turn.summarized()).isTrue();
		assertThat(turn.summaryVersion()).isEqualTo(1);
		assertThat(turn.messageCount()).isEqualTo(2);
		verify(repository).saveSummary(conversationId, 1, 2, summary);
		verify(repository).appendAssistantMessage(eq(conversationId), any());
	}

	private ResearchConversation conversation(UUID id, String summary, int messages, int summarized,
			int version, Instant now) {
		return new ResearchConversation(id, "Research", "ACTIVE", summary, messages, summarized,
				version, now, now);
	}

	private ResearchMessage message(UUID conversationId, int sequence, String role, String content, Instant now) {
		return new ResearchMessage(UUID.randomUUID(), conversationId, sequence, role, content,
				null, null, null, null, null, null, now);
	}
}
