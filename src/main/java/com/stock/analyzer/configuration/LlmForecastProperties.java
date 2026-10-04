package com.stock.analyzer.configuration;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("analysis.llm-forecast")
public class LlmForecastProperties {

	private boolean enabled;
	private String promptVersion = "llm-next-session-v1";
	private int upThreshold = 55;
	private int downThreshold = 45;
	private final WebResearch webResearch = new WebResearch();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getPromptVersion() {
		return promptVersion;
	}

	public void setPromptVersion(String promptVersion) {
		this.promptVersion = promptVersion;
	}

	public int getUpThreshold() {
		return upThreshold;
	}

	public void setUpThreshold(int upThreshold) {
		this.upThreshold = upThreshold;
	}

	public int getDownThreshold() {
		return downThreshold;
	}

	public void setDownThreshold(int downThreshold) {
		this.downThreshold = downThreshold;
	}

	public WebResearch getWebResearch() {
		return webResearch;
	}

	public static class WebResearch {

		private boolean enabled;
		private String baseUrl = "https://api.openai.com/v1";
		private String apiKey = "";
		private String model = "gpt-5.5";
		private int maxRequestsPerForecast = 1;
		private int maxSummaryCharacters = 6_000;
		private List<String> allowedDomains = new ArrayList<>(List.of(
				"sec.gov", "data.sec.gov", "federalreserve.gov", "bls.gov", "bea.gov"));
		private List<String> searchableCategories = new ArrayList<>(List.of(
				"NEWS", "REGULATORY", "COMPANY_EVENT", "EARNINGS", "MACRO"));

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}

		public String getApiKey() {
			return apiKey;
		}

		public void setApiKey(String apiKey) {
			this.apiKey = apiKey;
		}

		public String getModel() {
			return model;
		}

		public void setModel(String model) {
			this.model = model;
		}

		public int getMaxRequestsPerForecast() {
			return maxRequestsPerForecast;
		}

		public void setMaxRequestsPerForecast(int maxRequestsPerForecast) {
			this.maxRequestsPerForecast = maxRequestsPerForecast;
		}

		public int getMaxSummaryCharacters() {
			return maxSummaryCharacters;
		}

		public void setMaxSummaryCharacters(int maxSummaryCharacters) {
			this.maxSummaryCharacters = maxSummaryCharacters;
		}

		public List<String> getAllowedDomains() {
			return allowedDomains;
		}

		public void setAllowedDomains(List<String> allowedDomains) {
			this.allowedDomains = allowedDomains == null ? new ArrayList<>() : new ArrayList<>(allowedDomains);
		}

		public List<String> getSearchableCategories() {
			return searchableCategories;
		}

		public void setSearchableCategories(List<String> searchableCategories) {
			this.searchableCategories = searchableCategories == null
					? new ArrayList<>() : new ArrayList<>(searchableCategories);
		}
	}
}
