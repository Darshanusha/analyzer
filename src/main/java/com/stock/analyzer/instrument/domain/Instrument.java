package com.stock.analyzer.instrument.domain;

import java.time.LocalDate;
import java.util.UUID;

public record Instrument(
		UUID id,
		String symbol,
		String name,
		String exchange,
		String currency,
		boolean active,
		String sector,
		String industry,
		String permanentProviderId,
		String providerEntityType,
		LocalDate providerLastUpdated) {
}
