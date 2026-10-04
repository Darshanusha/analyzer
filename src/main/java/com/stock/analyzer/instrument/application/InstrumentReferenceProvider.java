package com.stock.analyzer.instrument.application;

import java.util.Optional;

import com.stock.analyzer.instrument.domain.InstrumentProfile;

public interface InstrumentReferenceProvider {

	Optional<InstrumentProfile> findBySymbol(String symbol);
}
