package com.stock.analyzer.forecast.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.stock.analyzer.analysis.domain.AnalysisComponentResult;
import com.stock.analyzer.configuration.LlmForecastProperties;
import com.stock.analyzer.forecast.domain.LlmCandidateFilterDecision;
import org.springframework.stereotype.Service;

@Service
public class LlmCandidateFilter {

	private final LlmForecastProperties.CandidateFilter properties;

	public LlmCandidateFilter(LlmForecastProperties properties) {
		this.properties = properties.getCandidateFilter();
	}

	public LlmCandidateFilterDecision evaluate(List<AnalysisComponentResult> components) {
		Double probabilityUp = metric(components, "prediction.next-session", "probabilityUp");
		if (probabilityUp != null) {
			boolean eligible = probabilityUp >= properties.getProbabilityThreshold();
			String reason = "Promoted Java model probabilityUp=" + probabilityUp
					+ " compared with threshold=" + properties.getProbabilityThreshold();
			return new LlmCandidateFilterDecision(eligible, "PROMOTED_MODEL", 0, 0,
					probabilityUp, List.of(reason));
		}

		List<String> reasons = new ArrayList<>();
		int technical = 0;
		int positive = 0;

		Double close = metric(components, "technical.daily", "adjustedClose");
		Double sma20 = metric(components, "technical.daily", "sma20");
		Double sma50 = metric(components, "technical.daily", "sma50");
		if (greater(close, sma20)) {
			positive++;
			technical++;
			reasons.add("Price is above SMA20");
		}
		if (greater(close, sma50)) {
			positive++;
			technical++;
			reasons.add("Price is above SMA50");
		}
		if (positive(metric(components, "technical.daily", "return20d"))) {
			positive++;
			technical++;
			reasons.add("20-session return is positive");
		}
		if (positive(metric(components, "market.regime-relative-strength", "relativeStrengthVsMarket20d"))) {
			positive++;
			reasons.add("20-session relative strength versus SPY is positive");
		}
		if (positive(metric(components, "market.regime-relative-strength", "relativeStrengthVsSector20d"))) {
			positive++;
			reasons.add("20-session relative strength versus sector is positive");
		}
		if (positive(metric(components, "fundamental.company", "revenueGrowth"))) {
			positive++;
			reasons.add("Revenue growth is positive");
		}
		if (positive(metric(components, "fundamental.company", "netIncomeGrowth"))) {
			positive++;
			reasons.add("Net-income growth is positive");
		}
		Double positiveNews = metric(components, "news.events", "positiveKeywordCount");
		Double negativeNews = metric(components, "news.events", "negativeKeywordCount");
		if (greater(positiveNews, negativeNews)) {
			positive++;
			reasons.add("Positive news-keyword count exceeds negative count");
		}

		boolean eligible = positive >= properties.getMinimumPositiveSignals()
				&& technical >= properties.getMinimumTechnicalSignals();
		reasons.add("Java candidate score=" + positive + ", technical signals=" + technical
				+ ", required=" + properties.getMinimumPositiveSignals() + "/"
				+ properties.getMinimumTechnicalSignals());
		return new LlmCandidateFilterDecision(eligible, "DETERMINISTIC_SIGNALS", positive, technical,
				null, reasons);
	}

	private Double metric(List<AnalysisComponentResult> components, String componentKey, String metricKey) {
		return components.stream().filter(component -> componentKey.equals(component.key())).findFirst()
				.map(AnalysisComponentResult::metrics).map(metrics -> number(metrics, metricKey)).orElse(null);
	}

	private Double number(Map<String, Object> metrics, String key) {
		Object value = metrics.get(key);
		return value instanceof Number number ? number.doubleValue() : null;
	}

	private boolean positive(Double value) {
		return value != null && value > 0;
	}

	private boolean greater(Double left, Double right) {
		return left != null && right != null && left > right;
	}
}
