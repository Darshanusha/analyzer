package com.stock.analyzer.forecast.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

@Component
public class LlmForecastPromptCatalog {

	private static final Pattern VERSION = Pattern.compile("[a-z0-9][a-z0-9._-]{0,49}");
	private final ResourceLoader resourceLoader;

	public LlmForecastPromptCatalog(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	public String load(String promptVersion) {
		if (promptVersion == null || !VERSION.matcher(promptVersion).matches()) {
			throw new IllegalArgumentException("Invalid LLM forecast prompt version: " + promptVersion);
		}
		Resource resource = resourceLoader.getResource("classpath:prompts/" + promptVersion + ".txt");
		if (!resource.exists()) {
			throw new IllegalStateException("No LLM forecast prompt resource exists for " + promptVersion);
		}
		try {
			String prompt = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8).strip();
			if (prompt.isBlank()) {
				throw new IllegalStateException("LLM forecast prompt resource is empty for " + promptVersion);
			}
			return prompt;
		}
		catch (IOException exception) {
			throw new IllegalStateException("LLM forecast prompt resource could not be read for " + promptVersion,
					exception);
		}
	}
}
