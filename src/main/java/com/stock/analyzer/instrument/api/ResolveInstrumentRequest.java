package com.stock.analyzer.instrument.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveInstrumentRequest(
		@NotBlank @Size(max = 15) String symbol,
		boolean refresh) {
}
