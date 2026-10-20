package com.stock.analyzer.agent.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendResearchMessageRequest(
		@NotBlank @Size(max = 20_000) String message) { }
