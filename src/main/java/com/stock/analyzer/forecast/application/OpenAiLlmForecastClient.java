package com.stock.analyzer.forecast.application;

import com.stock.analyzer.forecast.domain.LlmForecastCall;
import com.stock.analyzer.forecast.domain.LlmForecastOutput;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class OpenAiLlmForecastClient implements LlmForecastClient {

	private static final String SYSTEM_PROMPT = """
			You are a cautious evidence synthesizer evaluating whether a U.S. stock will close higher or lower
			on the next completed trading session. Use only the supplied point-in-time evidence. Never invent facts,
			prices, news, or events. The horizon is exactly one trading session.

			Return an upScore from 0 to 100 for comparative ranking: 0 is strongly downward, 50 is balanced or
			uncertain, and 100 is strongly upward. This score is an uncalibrated model judgment, not a statistical
			probability. Prefer scores near 50 because next-session movements are noisy. Confidence must be LOW,
			MEDIUM, or HIGH and describes evidence strength and completeness, not investment certainty.

			Explain the thesis using concise evidence present in the input. Include both bullish and bearish evidence,
			risks, and data limitations. Do not recommend buying, selling, position sizing, or executing a trade.

			If a specific missing fact could materially change the next-session assessment, add a structured
			informationRequests entry. Use category NEWS, REGULATORY, COMPANY_EVENT, EARNINGS, MACRO,
			OPTIONS, SHORT_INTEREST, INSIDER, or OTHER; importance LOW, MEDIUM, or HIGH; and provide a
			concise safe searchQuery only when public-web research could answer it. Do not request information
			already present. Return an empty informationRequests array when the supplied evidence is sufficient.
			""";

	private final ChatModel chatModel;
	private final ObservationRegistry observationRegistry;
	private final String configuredModel;

	public OpenAiLlmForecastClient(ObjectProvider<ChatModel> chatModelProvider,
			ObservationRegistry observationRegistry, Environment environment) {
		this.chatModel = chatModelProvider.getIfAvailable();
		this.observationRegistry = observationRegistry;
		this.configuredModel = environment.getProperty("spring.ai.openai.chat.options.model", "not-configured");
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
						.system(SYSTEM_PROMPT)
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
