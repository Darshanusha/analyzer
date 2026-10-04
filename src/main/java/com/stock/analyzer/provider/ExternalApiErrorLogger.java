package com.stock.analyzer.provider;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public final class ExternalApiErrorLogger {

	private static final int MAX_RESPONSE_LENGTH = 1_000;
	private static final Pattern NAMED_CREDENTIAL = Pattern.compile(
			"(?i)(api[_-]?key|apikey|token|x-api-key)(\\s*[=:]\\s*|\\\"\\s*:\\s*\\\")([^&\\s,\\\"}]+)");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private ExternalApiErrorLogger() {
	}

	public static void logFailure(Logger logger, String provider, String operation, String symbol,
			Object from, Object to, RestClientException exception, String... secrets) {
		if (exception instanceof RestClientResponseException response) {
			logger.warn("External API call failed: provider={}, operation={}, symbol={}, from={}, to={}, "
					+ "httpStatus={}, statusText={}, responseBody={}",
					provider, operation, symbol, from, to, response.getStatusCode().value(),
					sanitize(response.getStatusText(), secrets),
					sanitize(response.getResponseBodyAsString(), secrets));
			return;
		}
		logger.warn("External API call failed: provider={}, operation={}, symbol={}, from={}, to={}, failureType={}",
				provider, operation, symbol, from, to, exception.getClass().getSimpleName());
	}

	public static void logInvalidResponse(Logger logger, String provider, String operation, String symbol,
			String responseBody, Exception exception, String... secrets) {
		logger.warn("External API response could not be parsed: provider={}, operation={}, symbol={}, "
				+ "failureType={}, responseBody={}", provider, operation, symbol,
				exception.getClass().getSimpleName(), sanitize(responseBody, secrets));
	}

	static String sanitize(String value, String... secrets) {
		if (value == null || value.isBlank()) {
			return "<empty>";
		}
		String sanitized = value;
		if (secrets != null) {
			for (String secret : Arrays.stream(secrets).filter(item -> item != null && !item.isBlank()).toList()) {
				sanitized = sanitized.replace(secret, "***");
			}
		}
		sanitized = NAMED_CREDENTIAL.matcher(sanitized).replaceAll("$1$2***");
		sanitized = WHITESPACE.matcher(sanitized).replaceAll(" ").strip();
		return sanitized.substring(0, Math.min(MAX_RESPONSE_LENGTH, sanitized.length()));
	}
}
