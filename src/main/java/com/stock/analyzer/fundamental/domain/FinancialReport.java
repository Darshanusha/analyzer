package com.stock.analyzer.fundamental.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record FinancialReport(
		String dimension,
		LocalDate calendarDate,
		LocalDate providerDate,
		LocalDate reportPeriod,
		String fiscalPeriod,
		LocalDate providerLastUpdated,
		BigDecimal revenue,
		BigDecimal grossProfit,
		BigDecimal operatingIncome,
		BigDecimal netIncome,
		BigDecimal ebitda,
		BigDecimal freeCashFlow,
		BigDecimal operatingCashFlow,
		BigDecimal capitalExpenditure,
		BigDecimal assets,
		BigDecimal liabilities,
		BigDecimal equity,
		BigDecimal cashAndEquivalents,
		BigDecimal debt,
		BigDecimal dilutedShares,
		BigDecimal dilutedEps,
		BigDecimal grossMargin,
		BigDecimal netMargin,
		BigDecimal returnOnEquity,
		BigDecimal returnOnAssets,
		BigDecimal returnOnInvestedCapital,
		BigDecimal currentRatio,
		BigDecimal debtToEquity,
		BigDecimal priceEarnings,
		BigDecimal enterpriseValueEbitda,
		BigDecimal priceSales,
		BigDecimal marketCap,
		Map<String, Object> rawData) {
}
