package com.stock.analyzer.prediction.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stock.analyzer.instrument.domain.Instrument;
import com.stock.analyzer.marketdata.domain.DailyPriceBar;
import com.stock.analyzer.prediction.domain.FeatureSnapshot;
import org.springframework.stereotype.Service;

@Service
public class FeatureEngineeringService {

	public static final String FEATURE_SET_VERSION = "phase2-v1";
	public static final List<String> FEATURE_NAMES = List.of(
			"return_1d", "return_5d", "return_20d", "sma_20_ratio", "sma_50_ratio",
			"rsi_14", "volatility_20", "relative_volume_20", "market_return_20d",
			"relative_strength_market_20d");

	private final UsMarketCalendar marketCalendar;

	public FeatureEngineeringService(UsMarketCalendar marketCalendar) {
		this.marketCalendar = marketCalendar;
	}

	public FeatureSnapshot create(UUID analysisRunId, Instrument instrument, LocalDate cutoff,
			List<DailyPriceBar> stockPrices, List<DailyPriceBar> marketPrices) {
		List<DailyPriceBar> stock = valid(stockPrices, cutoff);
		List<DailyPriceBar> market = valid(marketPrices, cutoff);
		Map<String, Double> features = new LinkedHashMap<>();
		features.put("return_1d", change(stock, 1));
		features.put("return_5d", change(stock, 5));
		features.put("return_20d", change(stock, 20));
		features.put("sma_20_ratio", smaRatio(stock, 20));
		features.put("sma_50_ratio", smaRatio(stock, 50));
		features.put("rsi_14", rsi(stock, 14));
		features.put("volatility_20", volatility(stock, 20));
		features.put("relative_volume_20", relativeVolume(stock, 20));
		Double marketReturn = change(market, 20);
		features.put("market_return_20d", marketReturn);
		features.put("relative_strength_market_20d", subtract(features.get("return_20d"), marketReturn));

		List<String> missing = new ArrayList<>();
		features.forEach((name, value) -> {
			if (value == null || !Double.isFinite(value)) missing.add(name);
		});
		features.entrySet().removeIf(entry -> entry.getValue() == null || !Double.isFinite(entry.getValue()));
		return new FeatureSnapshot(UUID.randomUUID(), analysisRunId, instrument.id(), cutoff,
				marketCalendar.nextTradingSession(cutoff), FEATURE_SET_VERSION,
				missing.isEmpty() ? "COMPLETE" : "INCOMPLETE", Map.copyOf(features), List.copyOf(missing));
	}

	private List<DailyPriceBar> valid(List<DailyPriceBar> prices, LocalDate cutoff) {
		return prices.stream().filter(price -> !price.date().isAfter(cutoff) && close(price) != null)
				.sorted(Comparator.comparing(DailyPriceBar::date)).toList();
	}

	private Double change(List<DailyPriceBar> prices, int periods) {
		if (prices.size() <= periods) return null;
		double current = value(close(prices.getLast()));
		double prior = value(close(prices.get(prices.size() - 1 - periods)));
		return prior == 0 ? null : current / prior - 1.0;
	}

	private Double smaRatio(List<DailyPriceBar> prices, int periods) {
		if (prices.size() < periods) return null;
		double average = prices.subList(prices.size() - periods, prices.size()).stream()
				.map(this::close).mapToDouble(this::value).average().orElse(Double.NaN);
		return average == 0 ? null : value(close(prices.getLast())) / average - 1.0;
	}

	private Double rsi(List<DailyPriceBar> prices, int periods) {
		if (prices.size() <= periods) return null;
		double gains = 0;
		double losses = 0;
		for (int index = prices.size() - periods; index < prices.size(); index++) {
			double delta = value(close(prices.get(index))) - value(close(prices.get(index - 1)));
			if (delta >= 0) gains += delta; else losses -= delta;
		}
		if (losses == 0) return 1.0;
		double rs = gains / losses;
		return (100.0 - 100.0 / (1.0 + rs)) / 100.0;
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

	private Double relativeVolume(List<DailyPriceBar> prices, int periods) {
		if (prices.size() <= periods || prices.getLast().volume() == null) return null;
		double average = prices.subList(prices.size() - 1 - periods, prices.size() - 1).stream()
				.map(DailyPriceBar::volume).filter(java.util.Objects::nonNull)
				.mapToDouble(this::value).average().orElse(0);
		return average == 0 ? null : value(prices.getLast().volume()) / average;
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
}
