package com.stock.analyzer.fundamental.application;

import java.time.LocalDate;
import java.util.List;

import com.stock.analyzer.fundamental.domain.FinancialReport;

public interface FundamentalDataProvider {

	List<FinancialReport> fetchReports(String symbol, LocalDate from);
}
