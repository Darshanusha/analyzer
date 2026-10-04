package com.stock.analyzer.instrument.api;

import java.time.LocalDate;
import java.util.UUID;

import com.stock.analyzer.instrument.domain.Instrument;

public record InstrumentResponse(
		UUID instrumentId,
		String symbol,
		String name,
		String exchange,
		String currency,
		boolean active,
		String sector,
		String industry,
		String permanentProviderId,
		LocalDate providerLastUpdated) {

	static InstrumentResponse from(Instrument instrument) {
		return new InstrumentResponse(
				instrument.id(),
				instrument.symbol(),
				instrument.name(),
				instrument.exchange(),
				instrument.currency(),
				instrument.active(),
				instrument.sector(),
				instrument.industry(),
				instrument.permanentProviderId(),
				instrument.providerLastUpdated());
	}
}
