package com.stock.analyzer.provider.sharadar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record SharadarMarketDataResponse(int count, List<PriceRow> data) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	record PriceRow(
			String ticker,
			LocalDate date,
			BigDecimal open,
			BigDecimal high,
			BigDecimal low,
			BigDecimal close,
			BigDecimal volume,
			BigDecimal closeadj,
			BigDecimal closeunadj,
			LocalDate lastupdated) {
	}
}
