package com.stock.analyzer.agent.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.stock.analyzer.agent.application.ResearchAgentService;
import com.stock.analyzer.agent.domain.ResearchAgentTurn;
import com.stock.analyzer.agent.domain.ResearchConversation;
import com.stock.analyzer.agent.domain.ResearchMessage;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/conversations")
public class ResearchAgentController {

	private final ResearchAgentService service;

	public ResearchAgentController(ResearchAgentService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public ResearchConversation create(@Valid @RequestBody(required = false) CreateResearchConversationRequest request) {
		return service.create(request == null ? null : request.title());
	}

	@GetMapping
	public List<ResearchConversation> list(@RequestParam(defaultValue = "20") int limit) {
		return service.list(limit);
	}

	@GetMapping("/{conversationId}")
	public ResearchConversation get(@PathVariable UUID conversationId) {
		return service.get(conversationId);
	}

	@GetMapping("/{conversationId}/messages")
	public List<ResearchMessage> messages(@PathVariable UUID conversationId,
			@RequestParam(defaultValue = "100") int limit) {
		return service.messages(conversationId, limit);
	}

	@PostMapping("/{conversationId}/messages")
	public ResearchAgentTurn send(@PathVariable UUID conversationId,
			@Valid @RequestBody SendResearchMessageRequest request) {
		return service.send(conversationId, request.message());
	}
}
