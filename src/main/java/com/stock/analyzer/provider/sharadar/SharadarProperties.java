package com.stock.analyzer.provider.sharadar;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("providers.sharadar")
public class SharadarProperties {

	private URI baseUrl = URI.create("https://api.sharadar.com/v1.0");
	private String apiKey = "";

	public URI getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(URI baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}
}
