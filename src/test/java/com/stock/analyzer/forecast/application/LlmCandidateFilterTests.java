package com.stock.analyzer.forecast.application;

import java.util.List;
import java.util.Map;

import com.stock.analyzer.analysis.domain.AnalysisComponentResult;
import com.stock.analyzer.configuration.LlmForecastProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmCandidateFilterTests {

	private final LlmCandidateFilter filter = new LlmCandidateFilter(new LlmForecastProperties());

	@Test
	void acceptsPromotedModelAtConfiguredThreshold() {
		var decision = filter.evaluate(List.of(component("prediction.next-session",
				Map.of("probabilityUp", 0.50))));

		assertThat(decision.eligible()).isTrue();
		assertThat(decision.method()).isEqualTo("PROMOTED_MODEL");
	}

	@Test
	void acceptsThreeSignalsWhenAtLeastOneIsTechnical() {
		var decision = filter.evaluate(List.of(
				component("technical.daily", Map.of("adjustedClose", 110, "sma20", 100, "sma50", 120,
						"return20d", 0.02)),
				component("fundamental.company", Map.of("revenueGrowth", 0.10))));

		assertThat(decision.eligible()).isTrue();
		assertThat(decision.positiveSignalCount()).isEqualTo(3);
		assertThat(decision.technicalSignalCount()).isEqualTo(2);
	}

	@Test
	void rejectsFundamentalSignalsWithoutTechnicalConfirmation() {
		var decision = filter.evaluate(List.of(
				component("fundamental.company", Map.of("revenueGrowth", 0.10, "netIncomeGrowth", 0.20)),
				component("news.events", Map.of("positiveKeywordCount", 2, "negativeKeywordCount", 0))));

		assertThat(decision.positiveSignalCount()).isEqualTo(3);
		assertThat(decision.technicalSignalCount()).isZero();
		assertThat(decision.eligible()).isFalse();
	}

	private AnalysisComponentResult component(String key, Map<String, Object> metrics) {
		return new AnalysisComponentResult(key, "test", "AVAILABLE", "test", metrics,
				List.of(), List.of(), List.of(), 0L);
	}
}
