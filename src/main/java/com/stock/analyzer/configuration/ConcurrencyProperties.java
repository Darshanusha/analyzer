package com.stock.analyzer.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("analysis.concurrency")
public class ConcurrencyProperties {

	private int batchMaxConcurrentItems = 4;
	private int batchQueueCapacity = 600;
	private int openAiMaxConcurrentRequests = 4;
	private int selectionWebResearchMaxConcurrentRequests = 4;
	private int sharadarMaxConcurrentRequests = 2;
	private Duration permitTimeout = Duration.ofMinutes(5);
	private int transientMaxRetries = 2;
	private Duration initialRetryDelay = Duration.ofSeconds(1);
	private Duration maximumRetryDelay = Duration.ofSeconds(30);

	public int getBatchMaxConcurrentItems() { return batchMaxConcurrentItems; }
	public void setBatchMaxConcurrentItems(int value) { this.batchMaxConcurrentItems = value; }
	public int getBatchQueueCapacity() { return batchQueueCapacity; }
	public void setBatchQueueCapacity(int value) { this.batchQueueCapacity = value; }
	public int getOpenAiMaxConcurrentRequests() { return openAiMaxConcurrentRequests; }
	public void setOpenAiMaxConcurrentRequests(int value) { this.openAiMaxConcurrentRequests = value; }
	public int getSelectionWebResearchMaxConcurrentRequests() { return selectionWebResearchMaxConcurrentRequests; }
	public void setSelectionWebResearchMaxConcurrentRequests(int value) { this.selectionWebResearchMaxConcurrentRequests = value; }
	public int getSharadarMaxConcurrentRequests() { return sharadarMaxConcurrentRequests; }
	public void setSharadarMaxConcurrentRequests(int value) { this.sharadarMaxConcurrentRequests = value; }
	public Duration getPermitTimeout() { return permitTimeout; }
	public void setPermitTimeout(Duration value) { this.permitTimeout = value; }
	public int getTransientMaxRetries() { return transientMaxRetries; }
	public void setTransientMaxRetries(int value) { this.transientMaxRetries = value; }
	public Duration getInitialRetryDelay() { return initialRetryDelay; }
	public void setInitialRetryDelay(Duration value) { this.initialRetryDelay = value; }
	public Duration getMaximumRetryDelay() { return maximumRetryDelay; }
	public void setMaximumRetryDelay(Duration value) { this.maximumRetryDelay = value; }

	public void validate() {
		requireRange("BATCH_ANALYSIS_MAX_CONCURRENCY", batchMaxConcurrentItems, 1, 64);
		requireRange("BATCH_ANALYSIS_QUEUE_CAPACITY", batchQueueCapacity, 1, 10_000);
		requireRange("OPENAI_MAX_CONCURRENT_REQUESTS", openAiMaxConcurrentRequests, 1, 64);
		requireRange("LLM_SELECTION_WEB_RESEARCH_MAX_CONCURRENCY",
				selectionWebResearchMaxConcurrentRequests, 1, 64);
		requireRange("SHARADAR_MAX_CONCURRENT_REQUESTS", sharadarMaxConcurrentRequests, 1, 32);
		requireRange("EXTERNAL_REQUEST_MAX_RETRIES", transientMaxRetries, 0, 10);
		positive("EXTERNAL_REQUEST_PERMIT_TIMEOUT", permitTimeout);
		positive("EXTERNAL_REQUEST_INITIAL_RETRY_DELAY", initialRetryDelay);
		positive("EXTERNAL_REQUEST_MAX_RETRY_DELAY", maximumRetryDelay);
		if (initialRetryDelay.compareTo(maximumRetryDelay) > 0) {
			throw new IllegalStateException("EXTERNAL_REQUEST_INITIAL_RETRY_DELAY must not exceed EXTERNAL_REQUEST_MAX_RETRY_DELAY");
		}
	}

	private void requireRange(String name, int value, int minimum, int maximum) {
		if (value < minimum || value > maximum) {
			throw new IllegalStateException(name + " must be between " + minimum + " and " + maximum);
		}
	}

	private void positive(String name, Duration value) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalStateException(name + " must be positive");
		}
	}
}
