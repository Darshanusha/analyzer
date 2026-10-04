package com.stock.analyzer.provider.sharadar;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
record SharadarTickerResponse(int count, List<SharadarTickerRow> data) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SharadarTickerRow(
			@JsonProperty("table") String sourceTable,
			String permaticker,
			String ticker,
			String name,
			String exchange,
			String isdelisted,
			String category,
			String siccode,
			String sicsector,
			String sicindustry,
			String figi,
			String famaindustry,
			String sector,
			String industry,
			String currency,
			String location,
			LocalDate lastupdated,
			LocalDate firstadded,
			LocalDate firstpricedate,
			LocalDate lastpricedate,
			LocalDate firstquarter,
			LocalDate lastquarter,
			String secfilings,
			String companysite) {
	}
}
