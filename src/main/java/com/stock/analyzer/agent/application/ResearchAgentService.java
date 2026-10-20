package com.stock.analyzer.agent.application;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.stock.analyzer.agent.domain.ResearchAgentTurn;
import com.stock.analyzer.agent.domain.ResearchConversation;
import com.stock.analyzer.agent.domain.ResearchMessage;
import com.stock.analyzer.api.error.ApiException;
import com.stock.analyzer.configuration.ResearchAgentProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ResearchAgentService {

	private static final Logger log = LoggerFactory.getLogger(ResearchAgentService.class);
	private final ResearchConversationRepository repository;
	private final OpenAiResearchAgentClient client;
	private final ResearchAgentProperties properties;
	private final MeterRegistry meters;
	private final ConcurrentHashMap<UUID, ReentrantLock> conversationLocks = new ConcurrentHashMap<>();

	public ResearchAgentService(ResearchConversationRepository repository, OpenAiResearchAgentClient client,
			ResearchAgentProperties properties, MeterRegistry meters) {
		this.repository = repository;
		this.client = client;
		this.properties = properties;
		this.meters = meters;
	}

	public ResearchConversation create(String requestedTitle) {
		String title = requestedTitle == null || requestedTitle.isBlank()
				? "Stock research conversation" : requestedTitle.strip();
		if (title.length() > 200) throw new ApiException(HttpStatus.BAD_REQUEST, "AGENT_TITLE_TOO_LONG",
				"Conversation title may contain at most 200 characters");
		return repository.create(title);
	}

	public List<ResearchConversation> list(int limit) {
		return repository.findRecent(limit);
	}

	public ResearchConversation get(UUID conversationId) {
		return repository.find(conversationId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
				"AGENT_CONVERSATION_NOT_FOUND", "No research conversation was found for " + conversationId));
	}

	public List<ResearchMessage> messages(UUID conversationId, int limit) {
		get(conversationId);
		return repository.findMessages(conversationId, 0, limit);
	}

	public ResearchAgentTurn send(UUID conversationId, String content) {
		if (!client.isAvailable()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
				"RESEARCH_AGENT_NOT_CONFIGURED",
				"Enable the research agent and configure RESEARCH_AGENT_MODEL and OPENAI_API_KEY");
		if (content == null || content.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,
				"AGENT_MESSAGE_REQUIRED", "A non-empty message is required");
		String message = content.strip();
		if (message.length() > maxMessageCharacters()) throw new ApiException(HttpStatus.BAD_REQUEST,
				"AGENT_MESSAGE_TOO_LONG", "Message may contain at most " + maxMessageCharacters() + " characters");

		ReentrantLock lock = conversationLocks.computeIfAbsent(conversationId, ignored -> new ReentrantLock());
		lock.lock();
		try {
			ResearchConversation conversation = get(conversationId);
			if (!"ACTIVE".equals(conversation.status())) throw new ApiException(HttpStatus.CONFLICT,
					"AGENT_CONVERSATION_NOT_ACTIVE", "The research conversation is not active");
			ResearchMessage user = repository.appendUserMessage(conversationId, message);
			conversation = get(conversationId);
			List<ResearchMessage> context = repository.findMessages(conversationId,
					conversation.summarizedMessageCount(), 500);
			var response = invoke(conversation.summary(), context);
			ResearchMessage assistant = repository.appendAssistantMessage(conversationId, response);
			conversation = get(conversationId);
			boolean summarized = summarizeIfRequired(conversation);
			conversation = get(conversationId);
			meters.counter("stock.analysis.agent.turns", "status", "completed").increment();
			return new ResearchAgentTurn(conversationId, user, assistant, response.toolCalls(), summarized,
					conversation.summaryVersion(), conversation.messageCount());
		}
		finally {
			lock.unlock();
		}
	}

	private com.stock.analyzer.agent.domain.ResearchAgentResponse invoke(String summary,
			List<ResearchMessage> context) {
		try {
			return client.respond(summary, context);
		}
		catch (RuntimeException exception) {
			meters.counter("stock.analysis.agent.turns", "status", "failed").increment();
			log.error("Research-agent response failed", exception);
			throw new ApiException(HttpStatus.BAD_GATEWAY, "RESEARCH_AGENT_CALL_FAILED",
					"The research agent could not complete the OpenAI request");
		}
	}

	private boolean summarizeIfRequired(ResearchConversation conversation) {
		int unsummarized = conversation.messageCount() - conversation.summarizedMessageCount();
		if (unsummarized < summaryThreshold()) return false;
		int from = conversation.summarizedMessageCount() + 1;
		int through = conversation.messageCount();
		List<ResearchMessage> messages = repository.findMessages(conversation.conversationId(),
				conversation.summarizedMessageCount(), Math.max(summaryThreshold(), 500));
		try {
			var summary = client.summarize(conversation.summary(), messages);
			repository.saveSummary(conversation.conversationId(), from, through, summary);
			log.info("Research conversation summarized: conversationId={}, fromSequence={}, throughSequence={}, version={}",
					conversation.conversationId(), from, through, conversation.summaryVersion() + 1);
			return true;
		}
		catch (RuntimeException exception) {
			meters.counter("stock.analysis.agent.summaries", "status", "failed").increment();
			log.warn("Research conversation summary failed: conversationId={}, error={}",
					conversation.conversationId(), safe(exception.getMessage()));
			return false;
		}
	}

	private int summaryThreshold() {
		return Math.max(2, Math.min(properties.getSummaryThresholdMessages(), 500));
	}

	private int maxMessageCharacters() {
		return Math.max(100, Math.min(properties.getMaxMessageCharacters(), 20_000));
	}

	private String safe(String value) {
		if (value == null || value.isBlank()) return "Summary failed";
		String cleaned = value.replaceAll("\\s+", " ").strip();
		return cleaned.substring(0, Math.min(500, cleaned.length()));
	}
}
