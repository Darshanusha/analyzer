package com.stock.analyzer.provider.sharadar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record SharadarActionResponse(int count, List<ActionRow> data) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ActionRow(LocalDate date, String action, String ticker, String name, BigDecimal value,
			String contraticker, String contraname) {
	}
}
