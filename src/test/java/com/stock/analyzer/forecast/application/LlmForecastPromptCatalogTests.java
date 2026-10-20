package com.stock.analyzer.forecast.application;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmForecastPromptCatalogTests {

	private final LlmForecastPromptCatalog catalog =
			new LlmForecastPromptCatalog(new DefaultResourceLoader());

	@Test
	void loadsTheActiveVersionedPromptFromResources() {
		String prompt = catalog.load("llm-multi-horizon-v5");

		assertThat(prompt).contains("exactly 1 and exactly 5 completed")
				.contains("U.S. trading sessions")
				.contains("no more than five")
				.contains("database tool calls")
				.contains("When get_recent_news is unavailable")
				.contains("upsideTouchScore")
				.contains("downsideTouchScore")
				.doesNotContain("BUY");
	}

	@Test
	void failsClosedWhenAConfiguredPromptVersionDoesNotExist() {
		assertThatThrownBy(() -> catalog.load("llm-next-session-v1"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("No LLM forecast prompt resource exists");
	}

	@Test
	void rejectsUnsafePromptResourcePaths() {
		assertThatThrownBy(() -> catalog.load("../application"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Invalid LLM forecast prompt version");
	}
}
