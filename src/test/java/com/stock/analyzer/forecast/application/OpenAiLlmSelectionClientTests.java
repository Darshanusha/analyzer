package com.stock.analyzer.forecast.application;

import com.stock.analyzer.configuration.LlmSelectionProperties;
import com.stock.analyzer.configuration.ConcurrencyProperties;
import com.stock.analyzer.provider.ExternalRequestExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import java.util.concurrent.ExecutorService;

class OpenAiLlmSelectionClientTests {

	@Test
	void defaultsToResearchingAllFifteenCandidates() {
		LlmSelectionProperties properties = new LlmSelectionProperties();
		properties.setWebResearchEnabled(true);

		OpenAiLlmSelectionClient client = client(properties);

		assertThat(properties.getCandidateLimit()).isEqualTo(15);
		assertThat(client.maxWebRequests()).isEqualTo(15);
		assertThat(properties.isWebResearchRequired()).isTrue();
	}

	@Test
	void appliesAWebResearchSafetyCap() {
		LlmSelectionProperties properties = new LlmSelectionProperties();
		properties.setWebResearchMaxRequests(500);

		assertThat(client(properties).maxWebRequests()).isEqualTo(50);
	}

	@Test
	void loadsTheNewsAwareVersionedSelectionPrompt() {
		String prompt = new LlmForecastPromptCatalog(new DefaultResourceLoader())
				.load("llm-top-candidate-selection-v3");

		assertThat(prompt).contains("web-research result for every candidate")
				.contains("Select no candidate")
				.contains("upsideTouchScore")
				.contains("information cutoff");
	}

	private OpenAiLlmSelectionClient client(LlmSelectionProperties properties) {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		return new OpenAiLlmSelectionClient(properties, mock(LlmDatabaseToolExecutor.class),
				mock(OpenAiLlmSelectionWebResearchClient.class), new ObjectMapper(), meters,
				new LlmForecastPromptCatalog(new DefaultResourceLoader()),
				new ExternalRequestExecutor(new ConcurrencyProperties(), meters), mock(ExecutorService.class));
	}
}
