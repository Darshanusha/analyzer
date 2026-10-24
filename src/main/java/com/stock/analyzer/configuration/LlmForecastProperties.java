package com.stock.analyzer.configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("analysis.llm-forecast")
public class LlmForecastProperties {

	private boolean enabled;
	private String promptVersion = "llm-five-session-v6";
	private int upThreshold = 55;
	private int downThreshold = 45;
	private boolean recentNewsToolEnabled;
	private int maxDatabaseToolCalls = 5;
	private final CandidateFilter candidateFilter = new CandidateFilter();
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

	public CandidateFilter getCandidateFilter() {
		return candidateFilter;
	}

	public void setDownThreshold(int downThreshold) {
		this.downThreshold = downThreshold;
	}

	public WebResearch getWebResearch() {
		return webResearch;
	}

	public boolean isRecentNewsToolEnabled() {
		return recentNewsToolEnabled;
	}

	public void setRecentNewsToolEnabled(boolean recentNewsToolEnabled) {
		this.recentNewsToolEnabled = recentNewsToolEnabled;
	}

	public int getMaxDatabaseToolCalls() {
		return maxDatabaseToolCalls;
	}

	public void setMaxDatabaseToolCalls(int maxDatabaseToolCalls) {
		this.maxDatabaseToolCalls = maxDatabaseToolCalls;
	}

	public static class CandidateFilter {

		private double probabilityThreshold = 0.50;
		private int minimumPositiveSignals = 3;
		private int minimumTechnicalSignals = 1;

		public double getProbabilityThreshold() {
			return probabilityThreshold;
		}

		public void setProbabilityThreshold(double probabilityThreshold) {
			this.probabilityThreshold = probabilityThreshold;
		}

		public int getMinimumPositiveSignals() {
			return minimumPositiveSignals;
		}

		public void setMinimumPositiveSignals(int minimumPositiveSignals) {
			this.minimumPositiveSignals = minimumPositiveSignals;
		}

		public int getMinimumTechnicalSignals() {
			return minimumTechnicalSignals;
		}

		public void setMinimumTechnicalSignals(int minimumTechnicalSignals) {
			this.minimumTechnicalSignals = minimumTechnicalSignals;
		}
	}

	public static class WebResearch {

		private boolean enabled;
		private String baseUrl = "https://api.openai.com/v1";
		private String apiKey = "";
		private String model = "gpt-5.5";
		private String reasoningEffort = "low";
		private Duration connectTimeout = Duration.ofSeconds(10);
		private Duration readTimeout = Duration.ofSeconds(180);
		private int maxRequestsPerForecast = 5;
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

		public String getReasoningEffort() {
			return reasoningEffort;
		}

		public void setReasoningEffort(String reasoningEffort) {
			this.reasoningEffort = reasoningEffort;
		}

		public Duration getConnectTimeout() {
			return connectTimeout;
		}

		public void setConnectTimeout(Duration connectTimeout) {
			this.connectTimeout = connectTimeout;
		}

		public Duration getReadTimeout() {
			return readTimeout;
		}

		public void setReadTimeout(Duration readTimeout) {
			this.readTimeout = readTimeout;
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
