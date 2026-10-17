package com.stock.analyzer.forecast.application;

import com.stock.analyzer.forecast.domain.LlmForecastCall;
import com.stock.analyzer.forecast.domain.LlmForecastOutput;
import com.stock.analyzer.configuration.LlmForecastProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

public class OpenAiLlmForecastClient implements LlmForecastClient {

	private final ChatModel chatModel;
	private final ObservationRegistry observationRegistry;
	private final String configuredModel;
	private final String systemPrompt;

	public OpenAiLlmForecastClient(ObjectProvider<ChatModel> chatModelProvider,
			ObservationRegistry observationRegistry, Environment environment,
			LlmForecastProperties properties, LlmForecastPromptCatalog promptCatalog) {
		this.chatModel = chatModelProvider.getIfAvailable();
		this.observationRegistry = observationRegistry;
		this.configuredModel = environment.getProperty("spring.ai.openai.chat.options.model", "not-configured");
		this.systemPrompt = promptCatalog.load(properties.getPromptVersion());
	}

	@Override
	public boolean isAvailable() {
		return chatModel != null;
	}

	@Override
	public String configuredModel() {
		return configuredModel;
	}

	@Override
	public LlmForecastCall forecast(String evidenceJson) {
		if (chatModel == null) {
			throw new IllegalStateException("OpenAI chat model is not configured");
		}
		org.springframework.ai.chat.client.ResponseEntity<ChatResponse, LlmForecastOutput> response =
				ChatClient.create(chatModel, observationRegistry)
						.prompt()
						.system(systemPrompt)
						.user(evidenceJson)
						.call()
						.responseEntity(LlmForecastOutput.class,
								options -> options.useProviderStructuredOutput().validateSchema());
		LlmForecastOutput output = response.entity();
		if (output == null) {
			throw new IllegalStateException("OpenAI returned no structured forecast output");
		}
		ChatResponse chatResponse = response.response();
		if (chatResponse == null) {
			return new LlmForecastCall(output, configuredModel, null, null, null);
		}
		Usage usage = chatResponse.getMetadata().getUsage();
		String responseModel = chatResponse.getMetadata().getModel();
		return new LlmForecastCall(output,
				responseModel == null || responseModel.isBlank() ? configuredModel : responseModel,
				longValue(usage.getPromptTokens()), longValue(usage.getCompletionTokens()),
				longValue(usage.getTotalTokens()));
	}

	private Long longValue(Integer value) {
		return value == null ? null : value.longValue();
	}
}
