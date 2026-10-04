package com.stock.analyzer.marketdata.application;

import java.time.LocalDate;
import java.util.List;

import com.stock.analyzer.marketdata.domain.DailyPriceBar;

public interface MarketDataProvider {

	List<DailyPriceBar> fetchDailyPrices(String providerEntityType, String symbol, LocalDate from, LocalDate to);
}
