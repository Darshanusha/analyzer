package com.stock.analyzer.instrument.application;

import java.util.Optional;

import com.stock.analyzer.instrument.domain.Instrument;
import com.stock.analyzer.instrument.domain.InstrumentProfile;

public interface InstrumentRepository {

	Optional<Instrument> findActiveBySymbol(String symbol);

	Instrument upsert(InstrumentProfile profile);
}
