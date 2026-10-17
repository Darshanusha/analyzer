package com.stock.analyzer.forecast.application;

import com.stock.analyzer.configuration.LlmForecastProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OpenAiResponsesLlmForecastClientTests {

	@Test
	void excludesRecentNewsByDefaultAndUsesFiveCallBudgets() {
		LlmForecastProperties properties = new LlmForecastProperties();
		properties.getWebResearch().setEnabled(true);
		OpenAiResponsesLlmForecastClient client = client(properties);

		assertThat(client.availableDatabaseTools()).doesNotContain("get_recent_news");
		assertThat(client.maxDatabaseCalls()).isEqualTo(5);
		assertThat(client.maxWebSearchCalls()).isEqualTo(5);
	}

	@Test
	void enablingRecentNewsExposesItAndRestrictsWebSearchToOneCall() {
		LlmForecastProperties properties = new LlmForecastProperties();
		properties.setRecentNewsToolEnabled(true);
		properties.getWebResearch().setEnabled(true);
		OpenAiResponsesLlmForecastClient client = client(properties);

		assertThat(client.availableDatabaseTools()).contains("get_recent_news");
		assertThat(client.maxWebSearchCalls()).isEqualTo(1);
	}

	@Test
	void hardCapsConfiguredBudgetsAtFive() {
		LlmForecastProperties properties = new LlmForecastProperties();
		properties.setMaxDatabaseToolCalls(50);
		properties.getWebResearch().setEnabled(true);
		properties.getWebResearch().setMaxRequestsPerForecast(50);
		OpenAiResponsesLlmForecastClient client = client(properties);

		assertThat(client.maxDatabaseCalls()).isEqualTo(5);
		assertThat(client.maxWebSearchCalls()).isEqualTo(5);
	}

	private OpenAiResponsesLlmForecastClient client(LlmForecastProperties properties) {
		return new OpenAiResponsesLlmForecastClient(properties, mock(LlmDatabaseToolExecutor.class),
				new ObjectMapper(), new SimpleMeterRegistry(), ObservationRegistry.NOOP,
				new MockEnvironment().withProperty("spring.ai.openai.chat.options.model", "gpt-6-luna"),
				new LlmForecastPromptCatalog(new DefaultResourceLoader()));
	}
}
