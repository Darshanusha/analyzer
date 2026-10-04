package com.stock.analyzer.forecast.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateInformationGapRequest(
		@NotBlank String status,
		@Size(max = 2_000) String resolutionNotes) {
}
