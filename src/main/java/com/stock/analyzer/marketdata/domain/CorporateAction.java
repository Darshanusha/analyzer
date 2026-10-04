package com.stock.analyzer.marketdata.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record CorporateAction(
		LocalDate date,
		String actionType,
		String issuerName,
		BigDecimal value,
		String contraSymbol,
		String contraName,
		Map<String, Object> rawData) {

	public String providerRecordKey(String symbol) {
		return String.join("|", symbol, date.toString(), actionType,
				contraSymbol == null ? "" : contraSymbol, value == null ? "" : value.toPlainString());
	}
}
