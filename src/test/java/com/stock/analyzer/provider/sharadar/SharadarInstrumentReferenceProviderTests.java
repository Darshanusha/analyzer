package com.stock.analyzer.provider.sharadar;

import java.net.URI;

import com.stock.analyzer.api.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SharadarInstrumentReferenceProviderTests {

	@Test
	void mapsTheActiveStocksRowToCanonicalProfile() {
		SharadarProperties properties = properties("test-key");
		RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl().toString());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://api.sharadar.test/v1.0/data/tickers?format=json&ticker=AAPL"))
				.andExpect(header("x-api-key", "test-key"))
				.andRespond(withSuccess("""
						{"count":2,"data":[
						  {"table":"fundamentals","permaticker":"199059","ticker":"AAPL","name":"APPLE INC","exchange":"NASDAQ","isdelisted":"N"},
						  {"table":"stocks","permaticker":"199059","ticker":"AAPL","name":"APPLE INC","exchange":"NASDAQ","isdelisted":"N","sector":"Technology","industry":"Consumer Electronics","currency":"USD"}
						]}
						""", MediaType.APPLICATION_JSON));
		SharadarInstrumentReferenceProvider provider = new SharadarInstrumentReferenceProvider(
				properties, new ObjectMapper(), builder.build());

		var profile = provider.findBySymbol("AAPL").orElseThrow();

		assertThat(profile.provider()).isEqualTo("SHARADAR");
		assertThat(profile.providerEntityType()).isEqualTo("stocks");
		assertThat(profile.permanentProviderId()).isEqualTo("199059");
		assertThat(profile.symbol()).isEqualTo("AAPL");
		assertThat(profile.active()).isTrue();
		assertThat(profile.sector()).isEqualTo("Technology");
		assertThat(profile.rawMetadata()).containsEntry("table", "stocks");
		server.verify();
	}

	@Test
	void refusesRemoteLookupWhenApiKeyIsMissing() {
		SharadarProperties properties = properties(" ");
		SharadarInstrumentReferenceProvider provider = new SharadarInstrumentReferenceProvider(
				properties, new ObjectMapper(), RestClient.create());

		assertThatThrownBy(() -> provider.findBySymbol("AAPL"))
				.isInstanceOfSatisfying(ApiException.class, exception -> {
					assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(exception.code()).isEqualTo("SHARADAR_NOT_CONFIGURED");
				});
	}

	private SharadarProperties properties(String apiKey) {
		SharadarProperties properties = new SharadarProperties();
		properties.setBaseUrl(URI.create("https://api.sharadar.test/v1.0"));
		properties.setApiKey(apiKey);
		return properties;
	}
}
