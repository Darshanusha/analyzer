package com.stock.analyzer.provider.sharadar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record SharadarFundamentalResponse(int count, List<FundamentalRow> data) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	record FundamentalRow(
			String ticker, String dimension, LocalDate calendardate, LocalDate date, LocalDate reportperiod,
			String fiscalperiod, LocalDate lastupdated, BigDecimal revenue, BigDecimal gp, BigDecimal opinc,
			BigDecimal netinc, BigDecimal ebitda, BigDecimal fcf, BigDecimal ncfo, BigDecimal capex,
			BigDecimal assets, BigDecimal liabilities, BigDecimal equity, BigDecimal cashneq, BigDecimal debt,
			BigDecimal shareswadil, BigDecimal epsdil, BigDecimal grossmargin, BigDecimal netmargin,
			BigDecimal roe, BigDecimal roa, BigDecimal roic, BigDecimal currentratio, BigDecimal de,
			BigDecimal pe, BigDecimal evebitda, BigDecimal ps, BigDecimal marketcap) {
	}
}
