package com.stock.analyzer.forecast.application;

import com.stock.analyzer.forecast.domain.LlmForecastCall;

public interface LlmForecastClient {

	boolean isAvailable();

	String configuredModel();

	LlmForecastCall forecast(String evidenceJson);
}
