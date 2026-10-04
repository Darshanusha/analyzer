package com.stock.analyzer.provider.benzinga;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("providers.benzinga")
public class BenzingaProperties {

	private URI baseUrl = URI.create("https://api.benzinga.com");
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
