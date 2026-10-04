package com.stock.analyzer.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.stock.analyzer.fundamental.domain.FinancialReport;
import com.stock.analyzer.marketdata.domain.CorporateAction;
import com.stock.analyzer.marketdata.domain.DailyPriceBar;
import com.stock.analyzer.news.domain.NewsArticle;

public interface AnalysisDataRepository {

	Optional<LocalDate> latestPriceDate(UUID instrumentId);

	Optional<LocalDate> latestFundamentalProviderDate(UUID instrumentId);

	Optional<LocalDate> latestActionDate(UUID instrumentId);

	Optional<Instant> latestNewsUpdate(UUID instrumentId);

	int savePrices(UUID instrumentId, List<DailyPriceBar> prices);

	int saveFinancialReports(UUID instrumentId, List<FinancialReport> reports);

	int saveCorporateActions(UUID instrumentId, String symbol, List<CorporateAction> actions);

	int saveNews(UUID instrumentId, List<NewsArticle> news);

	List<DailyPriceBar> loadPrices(UUID instrumentId, LocalDate throughDate, int limit);

	List<FinancialReport> loadFinancialReports(UUID instrumentId, LocalDate availableThrough);

	List<NewsArticle> loadNews(UUID instrumentId, Instant publishedAfter);

	List<PeerSnapshot> loadPeers(UUID instrumentId, String sector, String industry, LocalDate availableThrough);

	void updateSyncState(UUID instrumentId, String provider, String dataset, LocalDate lastObservation,
			LocalDate lastProviderUpdate, String status, String error);

	void recordIngestion(UUID instrumentId, String provider, String dataset, LocalDate from, LocalDate to,
			String status, int received, int written, long durationMs, String errorCode, String errorMessage);
}
