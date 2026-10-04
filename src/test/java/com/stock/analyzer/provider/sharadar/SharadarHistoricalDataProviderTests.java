package com.stock.analyzer.provider.sharadar;

import java.net.URI;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SharadarHistoricalDataProviderTests {

	@Test
	void routesFundPriceRequestsToTheFundsTable() {
		SharadarProperties properties = properties();
		RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl().toString());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.sharadar.test/v1.0/data/funds?format=json&ticker=SPY"
				+ "&from=2026-09-01&to=2026-10-02&sort=date.asc"))
				.andExpect(header("x-api-key", "test-key"))
				.andRespond(withSuccess("""
						{"count":1,"data":[{"ticker":"SPY","date":"2026-10-02","open":670.0,
						"high":675.0,"low":668.0,"close":674.0,"volume":1000000,
						"closeadj":674.0,"closeunadj":674.0,"lastupdated":"2026-10-03"}]}
						""", MediaType.APPLICATION_JSON));
		SharadarHistoricalDataProvider provider = new SharadarHistoricalDataProvider(
				properties, new ObjectMapper(), builder.build());

		var prices = provider.fetchDailyPrices("funds", "SPY",
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 2));

		assertThat(prices).singleElement().satisfies(price -> {
			assertThat(price.date()).isEqualTo(LocalDate.of(2026, 10, 2));
			assertThat(price.fullyAdjustedClose()).isEqualByComparingTo("674.0");
		});
		server.verify();
	}

	@Test
	void routesEquityPriceRequestsToTheStocksTable() {
		SharadarProperties properties = properties();
		RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl().toString());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.sharadar.test/v1.0/data/stocks?format=json&ticker=AAPL"
				+ "&from=2026-10-01&to=2026-10-02&sort=date.asc"))
				.andRespond(withSuccess("{\"count\":0,\"data\":[]}", MediaType.APPLICATION_JSON));
		SharadarHistoricalDataProvider provider = new SharadarHistoricalDataProvider(
				properties, new ObjectMapper(), builder.build());

		assertThat(provider.fetchDailyPrices("stocks", "AAPL",
				LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2))).isEmpty();
		server.verify();
	}

	private SharadarProperties properties() {
		SharadarProperties properties = new SharadarProperties();
		properties.setBaseUrl(URI.create("https://api.sharadar.test/v1.0"));
		properties.setApiKey("test-key");
		return properties;
	}
}
