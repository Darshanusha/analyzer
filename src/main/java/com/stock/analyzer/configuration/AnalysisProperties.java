package com.stock.analyzer.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("analysis")
public class AnalysisProperties {

	private int historyYears = 10;
	private int newsLookbackDays = 30;
	private int defaultWaitSeconds = 60;
	private int maxWaitSeconds = 120;

	public int getHistoryYears() {
		return historyYears;
	}

	public void setHistoryYears(int historyYears) {
		this.historyYears = historyYears;
	}

	public int getNewsLookbackDays() {
		return newsLookbackDays;
	}

	public void setNewsLookbackDays(int newsLookbackDays) {
		this.newsLookbackDays = newsLookbackDays;
	}

	public int getDefaultWaitSeconds() {
		return defaultWaitSeconds;
	}

	public void setDefaultWaitSeconds(int defaultWaitSeconds) {
		this.defaultWaitSeconds = defaultWaitSeconds;
	}

	public int getMaxWaitSeconds() {
		return maxWaitSeconds;
	}

	public void setMaxWaitSeconds(int maxWaitSeconds) {
		this.maxWaitSeconds = maxWaitSeconds;
	}
}
