package com.stock.analyzer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class StaticDashboardTests {

	@Test
	void dashboardContainsPrimaryAnalysisWorkflow() throws IOException {
		String html = resource("static/index.html");
		String script = resource("static/app.js");

		assertThat(html).contains("id=\"singleForm\"", "id=\"batchForm\"", "id=\"rankings\"",
				"id=\"selectedCandidates\"", "id=\"pollIntervalSeconds\"", "value=\"30\"",
				"id=\"toggleRankings\"", "id=\"rankingsList\"");
		assertThat(script).contains("/api/v1/analyses", "/api/v1/batches", "/llm-rankings?horizon=5",
				"/llm-selections", "DEFAULT_POLL_SECONDS = 30", "pollIntervalSeconds() * 1000");
	}

	private String resource(String path) throws IOException {
		try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
			assertThat(stream).as("classpath resource %s", path).isNotNull();
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
