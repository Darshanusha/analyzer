package com.stock.analyzer.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("analysis.research-agent")
public class ResearchAgentProperties {

	private boolean enabled;
	private String model = "gpt-6.1-sol";
	private String reasoningEffort = "medium";
	private String summaryModel = "gpt-6.1-sol";
	private String summaryReasoningEffort = "low";
	private int summaryThresholdMessages = 20;
	private int maxToolCalls = 12;
	private int maxRounds = 14;
	private int maxMessageCharacters = 8_000;
	private int maxResponseTokens = 4_000;
	private int maxSummaryTokens = 1_600;
	private String baseUrl = "https://api.openai.com/v1";
	private String apiKey = "";
	private Duration connectTimeout = Duration.ofSeconds(10);
	private Duration readTimeout = Duration.ofSeconds(180);

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public String getModel() { return model; }
	public void setModel(String model) { this.model = model; }
	public String getReasoningEffort() { return reasoningEffort; }
	public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
	public String getSummaryModel() { return summaryModel; }
	public void setSummaryModel(String summaryModel) { this.summaryModel = summaryModel; }
	public String getSummaryReasoningEffort() { return summaryReasoningEffort; }
	public void setSummaryReasoningEffort(String summaryReasoningEffort) { this.summaryReasoningEffort = summaryReasoningEffort; }
	public int getSummaryThresholdMessages() { return summaryThresholdMessages; }
	public void setSummaryThresholdMessages(int summaryThresholdMessages) { this.summaryThresholdMessages = summaryThresholdMessages; }
	public int getMaxToolCalls() { return maxToolCalls; }
	public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }
	public int getMaxRounds() { return maxRounds; }
	public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
	public int getMaxMessageCharacters() { return maxMessageCharacters; }
	public void setMaxMessageCharacters(int maxMessageCharacters) { this.maxMessageCharacters = maxMessageCharacters; }
	public int getMaxResponseTokens() { return maxResponseTokens; }
	public void setMaxResponseTokens(int maxResponseTokens) { this.maxResponseTokens = maxResponseTokens; }
	public int getMaxSummaryTokens() { return maxSummaryTokens; }
	public void setMaxSummaryTokens(int maxSummaryTokens) { this.maxSummaryTokens = maxSummaryTokens; }
	public String getBaseUrl() { return baseUrl; }
	public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
	public String getApiKey() { return apiKey; }
	public void setApiKey(String apiKey) { this.apiKey = apiKey; }
	public Duration getConnectTimeout() { return connectTimeout; }
	public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
	public Duration getReadTimeout() { return readTimeout; }
	public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
}
