package com.stock.analyzer.application.synchronization;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.stock.analyzer.configuration.AnalysisProperties;
import com.stock.analyzer.fundamental.application.FundamentalDataProvider;
import com.stock.analyzer.fundamental.domain.FinancialReport;
import com.stock.analyzer.instrument.domain.Instrument;
import com.stock.analyzer.marketdata.application.CorporateActionProvider;
import com.stock.analyzer.marketdata.application.MarketDataProvider;
import com.stock.analyzer.marketdata.domain.CorporateAction;
import com.stock.analyzer.marketdata.domain.DailyPriceBar;
import com.stock.analyzer.news.application.NewsProvider;
import com.stock.analyzer.news.domain.NewsArticle;
import com.stock.analyzer.persistence.AnalysisDataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OnDemandDataSynchronizer {

	private final MarketDataProvider marketDataProvider;
	private final FundamentalDataProvider fundamentalDataProvider;
	private final CorporateActionProvider corporateActionProvider;
	private final NewsProvider newsProvider;
	private final AnalysisDataRepository repository;
	private final AnalysisProperties properties;
	private final Clock clock;

	@Autowired
	public OnDemandDataSynchronizer(MarketDataProvider marketDataProvider,
			FundamentalDataProvider fundamentalDataProvider, CorporateActionProvider corporateActionProvider,
			NewsProvider newsProvider, AnalysisDataRepository repository, AnalysisProperties properties) {
		this(marketDataProvider, fundamentalDataProvider, corporateActionProvider, newsProvider, repository,
				properties, Clock.systemUTC());
	}

	OnDemandDataSynchronizer(MarketDataProvider marketDataProvider, FundamentalDataProvider fundamentalDataProvider,
			CorporateActionProvider corporateActionProvider, NewsProvider newsProvider,
			AnalysisDataRepository repository, AnalysisProperties properties, Clock clock) {
		this.marketDataProvider = marketDataProvider;
		this.fundamentalDataProvider = fundamentalDataProvider;
		this.corporateActionProvider = corporateActionProvider;
		this.newsProvider = newsProvider;
		this.repository = repository;
		this.properties = properties;
		this.clock = clock;
	}

	public SyncSummary synchronize(Instrument instrument) {
		LocalDate today = LocalDate.now(clock);
		LocalDate initialFrom = today.minusYears(properties.getHistoryYears());
		List<String> limitations = new ArrayList<>();

		LocalDate priceFrom = repository.latestPriceDate(instrument.id()).map(date -> date.minusDays(7))
				.orElse(initialFrom);
		List<DailyPriceBar> prices = timedFetch(instrument, "SHARADAR", "prices", priceFrom, today,
				() -> marketDataProvider.fetchDailyPrices(instrument.providerEntityType(), instrument.symbol(),
						priceFrom, today),
				items -> repository.savePrices(instrument.id(), items));

		LocalDate fundamentalFrom = repository.latestFundamentalProviderDate(instrument.id())
				.map(date -> date.minusDays(45)).orElse(initialFrom);
		List<FinancialReport> reports = timedFetch(instrument, "SHARADAR", "fundamentals", fundamentalFrom, today,
				() -> fundamentalDataProvider.fetchReports(instrument.symbol(), fundamentalFrom),
				items -> repository.saveFinancialReports(instrument.id(), items));

		LocalDate actionsFrom = repository.latestActionDate(instrument.id()).map(date -> date.minusDays(7))
				.orElse(initialFrom);
		List<CorporateAction> actions = timedFetch(instrument, "SHARADAR", "actions", actionsFrom, today,
				() -> corporateActionProvider.fetchActions(instrument.symbol(), actionsFrom, today),
				items -> repository.saveCorporateActions(instrument.id(), instrument.symbol(), items));

		List<NewsArticle> news = List.of();
		if (newsProvider.isConfigured()) {
			Instant newsFrom = repository.latestNewsUpdate(instrument.id())
					.orElse(Instant.now(clock).minusSeconds(properties.getNewsLookbackDays() * 86_400L));
			news = timedFetch(instrument, "BENZINGA", "news", null, today,
					() -> newsProvider.fetchNews(instrument.symbol(), newsFrom),
					items -> repository.saveNews(instrument.id(), items));
		}
		else {
			limitations.add("Benzinga is not configured; news analysis uses no current articles");
		}

		LocalDate priceCutoff = repository.latestPriceDate(instrument.id()).orElse(null);
		return new SyncSummary(priceCutoff, prices.size(), reports.size(), actions.size(), news.size(), limitations);
	}

	/**
	 * Synchronizes only EOD prices for market and sector benchmarks. This avoids
	 * spending provider quota on fundamentals, actions, and news that are not used
	 * when a benchmark is evaluated.
	 */
	public LocalDate synchronizePricesOnly(Instrument instrument) {
		LocalDate today = LocalDate.now(clock);
		LocalDate priceFrom = repository.latestPriceDate(instrument.id()).map(date -> date.minusDays(7))
				.orElse(today.minusYears(properties.getHistoryYears()));
		timedFetch(instrument, "SHARADAR", "prices", priceFrom, today,
				() -> marketDataProvider.fetchDailyPrices(instrument.providerEntityType(), instrument.symbol(),
						priceFrom, today),
				items -> repository.savePrices(instrument.id(), items));
		return repository.latestPriceDate(instrument.id()).orElse(null);
	}

	private <T> List<T> timedFetch(Instrument instrument, String provider, String dataset, LocalDate from,
			LocalDate to, FetchOperation<T> fetch, SaveOperation<T> save) {
		long started = System.nanoTime();
		try {
			List<T> records = fetch.fetch();
			int written = save.save(records);
			LocalDate observation = observationDate(records);
			repository.updateSyncState(instrument.id(), provider, dataset, observation, null, "SUCCESS", null);
			repository.recordIngestion(instrument.id(), provider, dataset, from, to, "SUCCESS", records.size(), written,
					elapsedMillis(started), null, null);
			return records;
		}
		catch (RuntimeException exception) {
			repository.updateSyncState(instrument.id(), provider, dataset, null, null, "FAILED", safeMessage(exception));
			repository.recordIngestion(instrument.id(), provider, dataset, from, to, "FAILED", 0, 0,
					elapsedMillis(started), exception.getClass().getSimpleName(), safeMessage(exception));
			throw exception;
		}
	}

	private LocalDate observationDate(List<?> records) {
		return records.stream().map(record -> {
			if (record instanceof DailyPriceBar price) return price.date();
			if (record instanceof FinancialReport report) return report.providerDate();
			if (record instanceof CorporateAction action) return action.date();
			if (record instanceof NewsArticle article) return article.publishedAt().atZone(ZoneOffset.UTC).toLocalDate();
			return null;
		}).filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
	}

	private long elapsedMillis(long started) {
		return (System.nanoTime() - started) / 1_000_000L;
	}

	private String safeMessage(RuntimeException exception) {
		String message = exception.getMessage();
		return message == null ? exception.getClass().getSimpleName() : message.substring(0, Math.min(500, message.length()));
	}

	@FunctionalInterface
	private interface FetchOperation<T> {
		List<T> fetch();
	}

	@FunctionalInterface
	private interface SaveOperation<T> {
		int save(List<T> records);
	}
}
