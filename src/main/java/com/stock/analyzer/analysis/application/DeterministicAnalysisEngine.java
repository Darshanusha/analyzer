package com.stock.analyzer.analysis.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.stock.analyzer.analysis.domain.AnalysisComponentResult;
import com.stock.analyzer.application.synchronization.SyncSummary;
import com.stock.analyzer.fundamental.domain.FinancialReport;
import com.stock.analyzer.instrument.domain.Instrument;
import com.stock.analyzer.marketdata.domain.DailyPriceBar;
import com.stock.analyzer.news.domain.NewsArticle;
import com.stock.analyzer.persistence.PeerSnapshot;
import org.springframework.stereotype.Service;

@Service
public class DeterministicAnalysisEngine {

	private static final String VERSION = "phase1-v1";
	private static final List<String> POSITIVE_NEWS = List.of(
			"beat", "raises", "raised", "growth", "approval", "approved", "launch", "buyback", "record");
	private static final List<String> NEGATIVE_NEWS = List.of(
			"miss", "cuts", "cut", "lawsuit", "probe", "recall", "breach", "downgrade", "warning");
	private static final List<String> MATERIAL_NEWS = List.of(
			"earnings", "guidance", "merger", "acquisition", "lawsuit", "probe", "recall", "breach",
			"ceo", "cfo", "dividend", "buyback", "offering");

	private final Clock clock;

	public DeterministicAnalysisEngine() {
		this(Clock.systemUTC());
	}

	DeterministicAnalysisEngine(Clock clock) {
		this.clock = clock;
	}

	public AnalysisComponentResult fundamental(List<FinancialReport> reports) {
		long started = System.nanoTime();
		List<FinancialReport> annual = reports.stream()
				.filter(report -> "ART".equals(report.dimension()) || "ARY".equals(report.dimension()))
				.sorted(Comparator.comparing(FinancialReport::providerDate).reversed()).toList();
		if (annual.isEmpty()) {
			return unavailable("fundamental.company", "No point-in-time annual or trailing financial report is available",
					List.of("Sharadar returned no ART/ARY observations at the analysis cutoff"), started);
		}
		FinancialReport latest = annual.getFirst();
		FinancialReport prior = annual.stream()
				.filter(report -> report.reportPeriod().isBefore(latest.reportPeriod().minusMonths(9)))
				.findFirst().orElse(null);
		Map<String, Object> metrics = new LinkedHashMap<>();
		put(metrics, "reportPeriod", latest.reportPeriod());
		put(metrics, "availableDate", latest.providerDate());
		put(metrics, "revenue", latest.revenue());
		put(metrics, "revenueGrowth", growth(latest.revenue(), prior == null ? null : prior.revenue()));
		put(metrics, "netIncome", latest.netIncome());
		put(metrics, "netIncomeGrowth", growth(latest.netIncome(), prior == null ? null : prior.netIncome()));
		put(metrics, "freeCashFlow", latest.freeCashFlow());
		put(metrics, "freeCashFlowGrowth", growth(latest.freeCashFlow(), prior == null ? null : prior.freeCashFlow()));
		put(metrics, "grossMargin", latest.grossMargin());
		put(metrics, "netMargin", latest.netMargin());
		put(metrics, "returnOnEquity", latest.returnOnEquity());
		put(metrics, "returnOnInvestedCapital", latest.returnOnInvestedCapital());
		put(metrics, "currentRatio", latest.currentRatio());
		put(metrics, "debtToEquity", latest.debtToEquity());
		put(metrics, "priceEarnings", latest.priceEarnings());
		put(metrics, "enterpriseValueEbitda", latest.enterpriseValueEbitda());
		put(metrics, "priceSales", latest.priceSales());
		List<String> reasons = new ArrayList<>();
		addDirection(reasons, "Revenue", (BigDecimal) metrics.get("revenueGrowth"));
		addDirection(reasons, "Net income", (BigDecimal) metrics.get("netIncomeGrowth"));
		addLevel(reasons, "Gross margin", latest.grossMargin());
		addLevel(reasons, "Return on invested capital", latest.returnOnInvestedCapital());
		if (latest.debtToEquity() != null) {
			reasons.add("Debt-to-equity is " + percent(latest.debtToEquity()));
		}
		return result("fundamental.company", "AVAILABLE",
				"Latest point-in-time financials cover " + latest.reportPeriod(), metrics, reasons,
				List.of("Sharadar " + latest.dimension() + " report available on " + latest.providerDate()),
				prior == null ? List.of("A comparable prior annual observation was unavailable") : List.of(), started);
	}

	public AnalysisComponentResult technical(List<DailyPriceBar> prices) {
		long started = System.nanoTime();
		List<DailyPriceBar> valid = prices.stream().filter(price -> close(price) != null)
				.sorted(Comparator.comparing(DailyPriceBar::date)).toList();
		if (valid.size() < 50) {
			return unavailable("technical.daily", "Insufficient EOD price history for technical analysis",
					List.of("At least 50 valid daily closes are required; found " + valid.size()), started);
		}
		double last = value(close(valid.getLast()));
		Double sma20 = sma(valid, 20);
		Double sma50 = sma(valid, 50);
		Double sma200 = valid.size() >= 200 ? sma(valid, 200) : null;
		Double rsi14 = rsi(valid, 14);
		Double return1d = change(valid, 1);
		Double return20d = change(valid, 20);
		Double volatility20 = volatility(valid, 20);
		Double atr14 = atr(valid, 14);
		Double relativeVolume20 = relativeVolume(valid, 20);
		List<DailyPriceBar> year = valid.subList(Math.max(0, valid.size() - 252), valid.size());
		double low52 = year.stream().map(this::close).mapToDouble(this::value).min().orElse(last);
		double high52 = year.stream().map(this::close).mapToDouble(this::value).max().orElse(last);
		Double position52 = high52 == low52 ? null : (last - low52) / (high52 - low52);
		Map<String, Object> metrics = new LinkedHashMap<>();
		metrics.put("priceDate", valid.getLast().date());
		metrics.put("adjustedClose", round(last));
		put(metrics, "sma20", round(sma20));
		put(metrics, "sma50", round(sma50));
		put(metrics, "sma200", round(sma200));
		put(metrics, "rsi14", round(rsi14));
		put(metrics, "return1d", round(return1d));
		put(metrics, "return20d", round(return20d));
		put(metrics, "annualizedVolatility20", round(volatility20));
		put(metrics, "atr14", round(atr14));
		put(metrics, "relativeVolume20", round(relativeVolume20));
		put(metrics, "positionIn52WeekRange", round(position52));
		metrics.put("support20", round(year(valid, 20).stream().map(this::close).mapToDouble(this::value).min().orElse(last)));
		metrics.put("resistance20", round(year(valid, 20).stream().map(this::close).mapToDouble(this::value).max().orElse(last)));
		List<String> reasons = new ArrayList<>();
		reasons.add(last > sma20 ? "Price is above its 20-session average" : "Price is below its 20-session average");
		reasons.add(last > sma50 ? "Price is above its 50-session average" : "Price is below its 50-session average");
		if (sma200 != null) reasons.add(last > sma200 ? "Price is above its 200-session average" : "Price is below its 200-session average");
		if (rsi14 != null) reasons.add(rsi14 > 70 ? "RSI indicates strong/overbought momentum" : rsi14 < 30
				? "RSI indicates weak/oversold momentum" : "RSI is in a neutral range");
		return result("technical.daily", "AVAILABLE", "Daily technical indicators calculated through " + valid.getLast().date(),
				metrics, reasons, List.of("Fully adjusted close is used for returns when available"),
				sma200 == null ? List.of("Fewer than 200 sessions are available, so SMA200 is omitted") : List.of(), started);
	}

	public AnalysisComponentResult peerAndSector(Instrument instrument, FinancialReport latest,
			List<PeerSnapshot> peers) {
		long started = System.nanoTime();
		Map<String, Object> metrics = new LinkedHashMap<>();
		put(metrics, "sector", instrument.sector());
		put(metrics, "industry", instrument.industry());
		metrics.put("localPeerCount", peers.size());
		if (peers.isEmpty() || latest == null) {
			return result("fundamental.peer-sector", "LIMITED",
					"Sector classification is available, but no comparable locally synchronized peer set exists",
					metrics, List.of(), List.of(),
					List.of("Peer comparisons grow as additional companies in the same industry are analyzed"), started);
		}
		put(metrics, "peerMedianGrossMargin", median(peers.stream().map(PeerSnapshot::grossMargin).toList()));
		put(metrics, "peerMedianNetMargin", median(peers.stream().map(PeerSnapshot::netMargin).toList()));
		put(metrics, "peerMedianReturnOnEquity", median(peers.stream().map(PeerSnapshot::returnOnEquity).toList()));
		put(metrics, "peerMedianPriceEarnings", median(peers.stream().map(PeerSnapshot::priceEarnings).toList()));
		put(metrics, "peerMedianEnterpriseValueEbitda", median(peers.stream().map(PeerSnapshot::enterpriseValueEbitda).toList()));
		List<String> reasons = new ArrayList<>();
		compare(reasons, "Gross margin", latest.grossMargin(), (BigDecimal) metrics.get("peerMedianGrossMargin"));
		compare(reasons, "Net margin", latest.netMargin(), (BigDecimal) metrics.get("peerMedianNetMargin"));
		compare(reasons, "Return on equity", latest.returnOnEquity(), (BigDecimal) metrics.get("peerMedianReturnOnEquity"));
		return result("fundamental.peer-sector", "AVAILABLE", "Compared with " + peers.size() + " locally synchronized peers",
				metrics, reasons, peers.stream().map(PeerSnapshot::symbol).toList(),
				List.of("This is an on-demand local peer set, not the complete U.S. industry universe"), started);
	}

	public AnalysisComponentResult market(List<DailyPriceBar> stock, List<DailyPriceBar> market,
			List<DailyPriceBar> sector, String sectorSymbol) {
		long started = System.nanoTime();
		if (market.size() < 50 || stock.size() < 50) {
			return unavailable("market.regime-relative-strength", "Market comparison is unavailable",
					List.of("SPY or stock history contains fewer than 50 observations"), started);
		}
		Double stock20 = change(stock, 20);
		Double market20 = change(market, 20);
		Double sector20 = sector.size() >= 21 ? change(sector, 20) : null;
		double marketLast = value(close(market.getLast()));
		Double marketSma50 = sma(market, 50);
		Double marketSma200 = market.size() >= 200 ? sma(market, 200) : null;
		String regime = marketLast > marketSma50 && (marketSma200 == null || marketLast > marketSma200)
				? "UPTREND" : marketLast < marketSma50 && (marketSma200 == null || marketLast < marketSma200)
				? "DOWNTREND" : "MIXED";
		Map<String, Object> metrics = new LinkedHashMap<>();
		metrics.put("marketBenchmark", "SPY");
		metrics.put("marketRegime", regime);
		put(metrics, "stockReturn20d", round(stock20));
		put(metrics, "marketReturn20d", round(market20));
		put(metrics, "relativeStrengthVsMarket20d", round(subtract(stock20, market20)));
		put(metrics, "sectorBenchmark", sectorSymbol);
		put(metrics, "sectorReturn20d", round(sector20));
		put(metrics, "relativeStrengthVsSector20d", round(subtract(stock20, sector20)));
		List<String> reasons = new ArrayList<>();
		reasons.add("The broad-market regime is " + regime.toLowerCase(Locale.ROOT));
		if (stock20 != null && market20 != null) reasons.add(stock20 > market20
				? "The stock outperformed SPY over 20 sessions" : "The stock underperformed SPY over 20 sessions");
		if (stock20 != null && sector20 != null) reasons.add(stock20 > sector20
				? "The stock outperformed its sector proxy over 20 sessions" : "The stock underperformed its sector proxy over 20 sessions");
		return result("market.regime-relative-strength", "AVAILABLE", "Market regime and relative strength calculated",
				metrics, reasons, List.of("SPY and " + sectorSymbol + " adjusted EOD prices"),
				sector20 == null ? List.of("Sector proxy history was unavailable") : List.of(), started);
	}

	public AnalysisComponentResult news(List<NewsArticle> articles, boolean configured) {
		long started = System.nanoTime();
		if (!configured) {
			return unavailable("news.events", "News analysis is disabled because Benzinga is not configured",
					List.of("Set BENZINGA_API_KEY to enable current news ingestion"), started);
		}
		int positive = 0;
		int negative = 0;
		int material = 0;
		List<String> evidence = new ArrayList<>();
		for (NewsArticle article : articles) {
			String text = (article.title() + " " + (article.teaser() == null ? "" : article.teaser())).toLowerCase(Locale.ROOT);
			if (containsAny(text, POSITIVE_NEWS)) positive++;
			if (containsAny(text, NEGATIVE_NEWS)) negative++;
			if (containsAny(text, MATERIAL_NEWS)) material++;
			if (evidence.size() < 10) evidence.add(article.publishedAt() + " — " + article.title());
		}
		Map<String, Object> metrics = new LinkedHashMap<>();
		metrics.put("articleCount", articles.size());
		metrics.put("positiveKeywordCount", positive);
		metrics.put("negativeKeywordCount", negative);
		metrics.put("potentiallyMaterialCount", material);
		List<String> reasons = List.of("Keyword classification found " + positive + " positive, " + negative
				+ " negative, and " + material + " potentially material articles");
		return result("news.events", articles.isEmpty() ? "LIMITED" : "AVAILABLE",
				articles.isEmpty() ? "No recent Benzinga articles were returned" : "Recent news was normalized and screened",
				metrics, reasons, evidence, List.of("Keyword screening is descriptive and is not a sentiment model"), started);
	}

	public AnalysisComponentResult quality(SyncSummary sync, List<DailyPriceBar> prices,
			List<FinancialReport> reports, boolean newsConfigured) {
		long started = System.nanoTime();
		List<String> limitations = new ArrayList<>(sync.limitations());
		if (prices.size() < 200) limitations.add("Fewer than 200 EOD observations are available");
		if (reports.isEmpty()) limitations.add("No point-in-time fundamental reports are available");
		if (sync.priceCutoff() == null) limitations.add("No completed EOD cutoff could be established");
		else if (ChronoUnit.DAYS.between(sync.priceCutoff(), LocalDate.now(clock)) > 7)
			limitations.add("Latest EOD price is more than seven calendar days old");
		if (!newsConfigured) limitations.add("News coverage is unavailable");
		String status = limitations.isEmpty() ? "GOOD" : reports.isEmpty() || prices.size() < 50 ? "INSUFFICIENT" : "LIMITED";
		Map<String, Object> metrics = new LinkedHashMap<>();
		metrics.put("priceObservationCount", prices.size());
		metrics.put("financialReportCount", reports.size());
		put(metrics, "priceCutoffDate", sync.priceCutoff());
		metrics.put("newsConfigured", newsConfigured);
		return result("quality.data", status, "Data quality is " + status.toLowerCase(Locale.ROOT), metrics,
				List.of(), List.of(), limitations, started);
	}

	private AnalysisComponentResult result(String key, String status, String summary, Map<String, Object> metrics,
			List<String> reasons, List<String> evidence, List<String> limitations, long started) {
		return new AnalysisComponentResult(key, VERSION, status, summary, metrics, reasons, evidence, limitations,
				(System.nanoTime() - started) / 1_000_000L);
	}

	private AnalysisComponentResult unavailable(String key, String summary, List<String> limitations, long started) {
		return result(key, "UNAVAILABLE", summary, Map.of(), List.of(), List.of(), limitations, started);
	}

	private BigDecimal growth(BigDecimal current, BigDecimal previous) {
		if (current == null || previous == null || previous.signum() == 0) return null;
		return current.subtract(previous).divide(previous.abs(), 8, RoundingMode.HALF_UP);
	}

	private void addDirection(List<String> reasons, String label, BigDecimal change) {
		if (change != null) reasons.add(label + (change.signum() >= 0 ? " increased " : " decreased ") + percent(change.abs()));
	}

	private void addLevel(List<String> reasons, String label, BigDecimal value) {
		if (value != null) reasons.add(label + " is " + percent(value));
	}

	private void compare(List<String> reasons, String label, BigDecimal company, BigDecimal peerMedian) {
		if (company != null && peerMedian != null) reasons.add(label + (company.compareTo(peerMedian) >= 0
				? " is above the local peer median" : " is below the local peer median"));
	}

	private String percent(BigDecimal value) {
		return value.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
	}

	private BigDecimal median(List<BigDecimal> values) {
		List<BigDecimal> sorted = values.stream().filter(java.util.Objects::nonNull).sorted().toList();
		if (sorted.isEmpty()) return null;
		int middle = sorted.size() / 2;
		return sorted.size() % 2 == 1 ? sorted.get(middle)
				: sorted.get(middle - 1).add(sorted.get(middle)).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
	}

	private Double sma(List<DailyPriceBar> prices, int periods) {
		if (prices.size() < periods) return null;
		return year(prices, periods).stream().map(this::close).mapToDouble(this::value).average().orElse(Double.NaN);
	}

	private Double change(List<DailyPriceBar> prices, int periods) {
		if (prices.size() <= periods) return null;
		double current = value(close(prices.getLast()));
		double prior = value(close(prices.get(prices.size() - 1 - periods)));
		return prior == 0 ? null : current / prior - 1.0;
	}

	private Double rsi(List<DailyPriceBar> prices, int periods) {
		if (prices.size() <= periods) return null;
		double gains = 0;
		double losses = 0;
		for (int index = prices.size() - periods; index < prices.size(); index++) {
			double delta = value(close(prices.get(index))) - value(close(prices.get(index - 1)));
			if (delta >= 0) gains += delta; else losses -= delta;
		}
		if (losses == 0) return 100.0;
		double rs = (gains / periods) / (losses / periods);
		return 100.0 - 100.0 / (1.0 + rs);
	}

	private Double volatility(List<DailyPriceBar> prices, int periods) {
		if (prices.size() <= periods) return null;
		List<Double> returns = new ArrayList<>();
		for (int index = prices.size() - periods; index < prices.size(); index++) {
			double current = value(close(prices.get(index)));
			double prior = value(close(prices.get(index - 1)));
			if (current > 0 && prior > 0) returns.add(Math.log(current / prior));
		}
		double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
		double variance = returns.stream().mapToDouble(value -> Math.pow(value - mean, 2)).sum()
				/ Math.max(1, returns.size() - 1);
		return Math.sqrt(variance) * Math.sqrt(252);
	}

	private Double atr(List<DailyPriceBar> prices, int periods) {
		if (prices.size() <= periods) return null;
		double total = 0;
		for (int index = prices.size() - periods; index < prices.size(); index++) {
			DailyPriceBar bar = prices.get(index);
			if (bar.high() == null || bar.low() == null) return null;
			double previousClose = value(close(prices.get(index - 1)));
			double range = Math.max(value(bar.high()) - value(bar.low()),
					Math.max(Math.abs(value(bar.high()) - previousClose), Math.abs(value(bar.low()) - previousClose)));
			total += range;
		}
		return total / periods;
	}

	private Double relativeVolume(List<DailyPriceBar> prices, int periods) {
		if (prices.size() <= periods || prices.getLast().volume() == null) return null;
		double average = prices.subList(prices.size() - 1 - periods, prices.size() - 1).stream()
				.map(DailyPriceBar::volume).filter(java.util.Objects::nonNull).mapToDouble(this::value).average().orElse(0);
		return average == 0 ? null : value(prices.getLast().volume()) / average;
	}

	private List<DailyPriceBar> year(List<DailyPriceBar> prices, int periods) {
		return prices.subList(Math.max(0, prices.size() - periods), prices.size());
	}

	private BigDecimal close(DailyPriceBar price) {
		return price.fullyAdjustedClose() != null ? price.fullyAdjustedClose() : price.close();
	}

	private double value(BigDecimal value) {
		return value.doubleValue();
	}

	private Double subtract(Double left, Double right) {
		return left == null || right == null ? null : left - right;
	}

	private Double round(Double value) {
		return value == null || value.isNaN() || value.isInfinite() ? null : Math.round(value * 1_000_000d) / 1_000_000d;
	}

	private void put(Map<String, Object> metrics, String key, Object value) {
		if (value != null) metrics.put(key, value);
	}

	private boolean containsAny(String text, List<String> terms) {
		return terms.stream().anyMatch(text::contains);
	}
}
