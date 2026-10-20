package com.stock.analyzer.agent.api;

import jakarta.validation.constraints.Size;

public record CreateResearchConversationRequest(@Size(max = 200) String title) { }
