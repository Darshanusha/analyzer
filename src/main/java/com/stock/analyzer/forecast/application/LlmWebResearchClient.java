package com.stock.analyzer.forecast.application;

import java.time.Instant;

import com.stock.analyzer.forecast.domain.LlmInformationRequest;
import com.stock.analyzer.forecast.domain.LlmWebResearchResult;

public interface LlmWebResearchClient {

	boolean isAvailable();

	LlmWebResearchResult research(String symbol, Instant informationCutoff, LlmInformationRequest request);
}
