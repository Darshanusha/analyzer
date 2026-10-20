package com.stock.analyzer.agent.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.stock.analyzer.agent.domain.ResearchAgentResponse;
import com.stock.analyzer.agent.domain.ResearchConversation;
import com.stock.analyzer.agent.domain.ResearchMessage;
import com.stock.analyzer.agent.domain.ResearchSummaryResult;

public interface ResearchConversationRepository {
	ResearchConversation create(String title);
	Optional<ResearchConversation> find(UUID conversationId);
	List<ResearchConversation> findRecent(int limit);
	List<ResearchMessage> findMessages(UUID conversationId, int afterSequence, int limit);
	ResearchMessage appendUserMessage(UUID conversationId, String content);
	ResearchMessage appendAssistantMessage(UUID conversationId, ResearchAgentResponse response);
	ResearchConversation saveSummary(UUID conversationId, int fromSequence, int throughSequence,
			ResearchSummaryResult result);
}
