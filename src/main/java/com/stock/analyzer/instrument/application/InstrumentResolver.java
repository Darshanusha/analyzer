package com.stock.analyzer.instrument.application;

import java.util.Locale;
import java.util.regex.Pattern;

import com.stock.analyzer.api.error.ApiException;
import com.stock.analyzer.instrument.domain.Instrument;
import com.stock.analyzer.instrument.domain.InstrumentProfile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class InstrumentResolver {

	private static final Pattern SUPPORTED_SYMBOL = Pattern.compile("[A-Z][A-Z0-9.-]{0,14}");

	private final InstrumentRepository instrumentRepository;
	private final InstrumentReferenceProvider instrumentReferenceProvider;

	public InstrumentResolver(InstrumentRepository instrumentRepository,
			InstrumentReferenceProvider instrumentReferenceProvider) {
		this.instrumentRepository = instrumentRepository;
		this.instrumentReferenceProvider = instrumentReferenceProvider;
	}

	public Instrument resolve(String submittedSymbol, boolean refresh) {
		String symbol = normalize(submittedSymbol);
		if (!refresh) {
			var existing = instrumentRepository.findActiveBySymbol(symbol);
			if (existing.isPresent()) {
				return existing.get();
			}
		}

		InstrumentProfile profile = instrumentReferenceProvider.findBySymbol(symbol)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INSTRUMENT_NOT_FOUND",
						"No supported U.S. instrument was found for symbol " + symbol));
		return instrumentRepository.upsert(profile);
	}

	private String normalize(String submittedSymbol) {
		String symbol = submittedSymbol == null ? "" : submittedSymbol.strip().toUpperCase(Locale.ROOT);
		if (!SUPPORTED_SYMBOL.matcher(symbol).matches()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SYMBOL",
					"Symbol must contain 1 to 15 uppercase letters, numbers, periods, or hyphens");
		}
		return symbol;
	}
}
