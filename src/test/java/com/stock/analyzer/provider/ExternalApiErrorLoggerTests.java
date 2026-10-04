package com.stock.analyzer.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalApiErrorLoggerTests {

	@Test
	void removesConfiguredSecretsAndNamedCredentialsFromProviderResponses() {
		String sanitized = ExternalApiErrorLogger.sanitize(
				"Exceeds free tier token=secret-token api_key=another-key x-api-key: third-key",
				"secret-token");

		assertThat(sanitized)
				.doesNotContain("secret-token", "another-key", "third-key")
				.contains("Exceeds free tier", "token=***", "api_key=***", "x-api-key: ***");
	}

	@Test
	void flattensAndLimitsProviderResponseText() {
		String sanitized = ExternalApiErrorLogger.sanitize("first line\nsecond line " + "x".repeat(2_000));

		assertThat(sanitized).startsWith("first line second line").hasSize(1_000);
	}
}
