package com.stock.analyzer.marketdata.application;

import java.time.LocalDate;
import java.util.List;

import com.stock.analyzer.marketdata.domain.CorporateAction;

public interface CorporateActionProvider {

	List<CorporateAction> fetchActions(String symbol, LocalDate from, LocalDate to);
}
