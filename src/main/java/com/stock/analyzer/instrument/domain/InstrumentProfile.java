package com.stock.analyzer.instrument.domain;

import java.time.LocalDate;
import java.util.Map;

public record InstrumentProfile(
		String provider,
		String providerEntityType,
		String permanentProviderId,
		String symbol,
		String name,
		String exchange,
		String currency,
		boolean active,
		String category,
		String sector,
		String industry,
		String sicCode,
		String sicSector,
		String sicIndustry,
		String famaIndustry,
		String compositeFigi,
		String location,
		String secFilingsUrl,
		String companyUrl,
		LocalDate firstAdded,
		LocalDate firstPriceDate,
		LocalDate lastPriceDate,
		LocalDate firstQuarter,
		LocalDate lastQuarter,
		LocalDate lastUpdated,
		Map<String, Object> rawMetadata) {
}
