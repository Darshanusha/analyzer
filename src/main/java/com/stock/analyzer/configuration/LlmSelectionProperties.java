package com.stock.analyzer.configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("analysis.llm-selection")
public class LlmSelectionProperties {

	private boolean enabled;
	private String model = "gpt-6.1-sol";
	private String reasoningEffort = "medium";
	private String promptVersion = "llm-top-candidate-selection-v2";
	private int candidateLimit = 15;
	private int selectionLimit = 3;
	private int maxDatabaseToolCalls = 12;
	private int maxRounds = 14;
	private boolean webResearchEnabled = true;
	private boolean webResearchRequired = true;
	private int webResearchMaxRequests = 15;
	private String webResearchModel = "gpt-6.1-sol";
	private String webResearchReasoningEffort = "low";
	private List<String> webResearchAllowedDomains = new ArrayList<>(List.of("sec.gov", "data.sec.gov",
			"reuters.com", "nasdaq.com", "nyse.com", "federalreserve.gov", "bls.gov", "bea.gov"));
	private int webResearchMaxSummaryCharacters = 4_000;
	private int defaultWaitSeconds = 60;
	private int maxWaitSeconds = 120;
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
	public String getPromptVersion() { return promptVersion; }
	public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
	public int getCandidateLimit() { return candidateLimit; }
	public void setCandidateLimit(int candidateLimit) { this.candidateLimit = candidateLimit; }
	public int getSelectionLimit() { return selectionLimit; }
	public void setSelectionLimit(int selectionLimit) { this.selectionLimit = selectionLimit; }
	public int getMaxDatabaseToolCalls() { return maxDatabaseToolCalls; }
	public void setMaxDatabaseToolCalls(int maxDatabaseToolCalls) { this.maxDatabaseToolCalls = maxDatabaseToolCalls; }
	public int getMaxRounds() { return maxRounds; }
	public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
	public boolean isWebResearchEnabled() { return webResearchEnabled; }
	public void setWebResearchEnabled(boolean webResearchEnabled) { this.webResearchEnabled = webResearchEnabled; }
	public boolean isWebResearchRequired() { return webResearchRequired; }
	public void setWebResearchRequired(boolean webResearchRequired) { this.webResearchRequired = webResearchRequired; }
	public int getWebResearchMaxRequests() { return webResearchMaxRequests; }
	public void setWebResearchMaxRequests(int webResearchMaxRequests) { this.webResearchMaxRequests = webResearchMaxRequests; }
	public String getWebResearchModel() { return webResearchModel; }
	public void setWebResearchModel(String webResearchModel) { this.webResearchModel = webResearchModel; }
	public String getWebResearchReasoningEffort() { return webResearchReasoningEffort; }
	public void setWebResearchReasoningEffort(String webResearchReasoningEffort) { this.webResearchReasoningEffort = webResearchReasoningEffort; }
	public List<String> getWebResearchAllowedDomains() { return webResearchAllowedDomains; }
	public void setWebResearchAllowedDomains(List<String> webResearchAllowedDomains) { this.webResearchAllowedDomains = webResearchAllowedDomains; }
	public int getWebResearchMaxSummaryCharacters() { return webResearchMaxSummaryCharacters; }
	public void setWebResearchMaxSummaryCharacters(int webResearchMaxSummaryCharacters) { this.webResearchMaxSummaryCharacters = webResearchMaxSummaryCharacters; }
	public int getDefaultWaitSeconds() { return defaultWaitSeconds; }
	public void setDefaultWaitSeconds(int defaultWaitSeconds) { this.defaultWaitSeconds = defaultWaitSeconds; }
	public int getMaxWaitSeconds() { return maxWaitSeconds; }
	public void setMaxWaitSeconds(int maxWaitSeconds) { this.maxWaitSeconds = maxWaitSeconds; }
	public String getBaseUrl() { return baseUrl; }
	public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
	public String getApiKey() { return apiKey; }
	public void setApiKey(String apiKey) { this.apiKey = apiKey; }
	public Duration getConnectTimeout() { return connectTimeout; }
	public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
	public Duration getReadTimeout() { return readTimeout; }
	public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
}
