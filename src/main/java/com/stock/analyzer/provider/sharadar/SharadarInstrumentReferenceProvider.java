package com.stock.analyzer.provider.sharadar;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.stock.analyzer.api.error.ApiException;
import com.stock.analyzer.instrument.application.InstrumentReferenceProvider;
import com.stock.analyzer.instrument.domain.InstrumentProfile;
import com.stock.analyzer.provider.ExternalApiErrorLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class SharadarInstrumentReferenceProvider implements InstrumentReferenceProvider {

	private static final String PROVIDER_KEY = "SHARADAR";
	private static final TypeReference<Map<String, Object>> RAW_METADATA_TYPE = new TypeReference<>() { };
	private static final Logger log = LoggerFactory.getLogger(SharadarInstrumentReferenceProvider.class);

	private final SharadarProperties properties;
	private final ObjectMapper objectMapper;
	private final RestClient restClient;

	@Autowired
	public SharadarInstrumentReferenceProvider(SharadarProperties properties, ObjectMapper objectMapper) {
		this(properties, objectMapper, RestClient.create(properties.getBaseUrl().toString()));
	}

	SharadarInstrumentReferenceProvider(SharadarProperties properties, ObjectMapper objectMapper,
			RestClient restClient) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.restClient = restClient;
	}

	@Override
	public Optional<InstrumentProfile> findBySymbol(String symbol) {
		if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHARADAR_NOT_CONFIGURED",
					"Sharadar is not configured. Set SHARADAR_API_KEY before resolving new symbols");
		}

		try {
			SharadarTickerResponse response = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.path("/data/tickers")
							.queryParam("format", "json")
							.queryParam("ticker", symbol)
							.build())
					.header("x-api-key", properties.getApiKey())
					.retrieve()
					.body(SharadarTickerResponse.class);

			if (response == null || response.data() == null || response.data().isEmpty()) {
				return Optional.empty();
			}

			return chooseSecurityMasterRow(response.data()).map(this::toProfile);
		}
		catch (RestClientResponseException exception) {
			ExternalApiErrorLogger.logFailure(log, "SHARADAR", "instrument-resolution", symbol,
					null, null, exception, properties.getApiKey());
			throw mapProviderResponseFailure(exception);
		}
		catch (RestClientException exception) {
			ExternalApiErrorLogger.logFailure(log, "SHARADAR", "instrument-resolution", symbol,
					null, null, exception, properties.getApiKey());
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHARADAR_UNAVAILABLE",
					"Sharadar could not be reached");
		}
	}

	private Optional<SharadarTickerResponse.SharadarTickerRow> chooseSecurityMasterRow(
			List<SharadarTickerResponse.SharadarTickerRow> rows) {
		return rows.stream()
				.filter(row -> row.permaticker() != null && row.ticker() != null)
				.min(Comparator
						.comparing((SharadarTickerResponse.SharadarTickerRow row) -> isDelisted(row) ? 1 : 0)
						.thenComparing(row -> sourcePriority(row.sourceTable())));
	}

	private int sourcePriority(String sourceTable) {
		if ("stocks".equalsIgnoreCase(sourceTable)) {
			return 0;
		}
		if ("fundamentals".equalsIgnoreCase(sourceTable)) {
			return 1;
		}
		return 2;
	}

	private boolean isDelisted(SharadarTickerResponse.SharadarTickerRow row) {
		return "Y".equalsIgnoreCase(row.isdelisted());
	}

	private InstrumentProfile toProfile(SharadarTickerResponse.SharadarTickerRow row) {
		return new InstrumentProfile(
				PROVIDER_KEY,
				row.sourceTable(),
				row.permaticker(),
				row.ticker(),
				row.name(),
				row.exchange(),
				row.currency(),
				!isDelisted(row),
				row.category(),
				row.sector(),
				row.industry(),
				row.siccode(),
				row.sicsector(),
				row.sicindustry(),
				row.famaindustry(),
				row.figi(),
				row.location(),
				row.secfilings(),
				row.companysite(),
				row.firstadded(),
				row.firstpricedate(),
				row.lastpricedate(),
				row.firstquarter(),
				row.lastquarter(),
				row.lastupdated(),
				objectMapper.convertValue(row, RAW_METADATA_TYPE));
	}

	private ApiException mapProviderResponseFailure(RestClientResponseException exception) {
		if (exception.getStatusCode().value() == 401) {
			return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHARADAR_AUTHENTICATION_FAILED",
					"Sharadar rejected the configured API key");
		}
		if (exception.getStatusCode().value() == 403) {
			return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHARADAR_ACCESS_DENIED",
					"Sharadar denied access; verify the subscription and API key");
		}
		if (exception.getStatusCode().value() == 429) {
			return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHARADAR_RATE_LIMITED",
					"Sharadar rate limit was reached; retry later");
		}
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHARADAR_REQUEST_FAILED",
				"Sharadar returned an unsuccessful response");
	}
}
