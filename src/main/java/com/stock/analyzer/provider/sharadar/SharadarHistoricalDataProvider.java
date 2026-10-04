package com.stock.analyzer.provider.sharadar;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.stock.analyzer.api.error.ApiException;
import com.stock.analyzer.fundamental.application.FundamentalDataProvider;
import com.stock.analyzer.fundamental.domain.FinancialReport;
import com.stock.analyzer.marketdata.application.CorporateActionProvider;
import com.stock.analyzer.marketdata.application.MarketDataProvider;
import com.stock.analyzer.marketdata.domain.CorporateAction;
import com.stock.analyzer.marketdata.domain.DailyPriceBar;
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
public class SharadarHistoricalDataProvider
		implements MarketDataProvider, FundamentalDataProvider, CorporateActionProvider {

	private static final TypeReference<Map<String, Object>> RAW_TYPE = new TypeReference<>() { };
	private static final Logger log = LoggerFactory.getLogger(SharadarHistoricalDataProvider.class);

	private final SharadarProperties properties;
	private final ObjectMapper objectMapper;
	private final RestClient restClient;

	@Autowired
	public SharadarHistoricalDataProvider(SharadarProperties properties, ObjectMapper objectMapper) {
		this(properties, objectMapper, RestClient.create(properties.getBaseUrl().toString()));
	}

	SharadarHistoricalDataProvider(SharadarProperties properties, ObjectMapper objectMapper, RestClient restClient) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.restClient = restClient;
	}

	@Override
	public List<DailyPriceBar> fetchDailyPrices(String providerEntityType, String symbol, LocalDate from, LocalDate to) {
		ensureConfigured();
		String table = priceTable(providerEntityType);
		try {
			SharadarMarketDataResponse response = restClient.get()
					.uri(builder -> builder.path("/data/").path(table)
							.queryParam("format", "json").queryParam("ticker", symbol)
							.queryParam("from", from).queryParam("to", to).queryParam("sort", "date.asc")
							.build())
					.header("x-api-key", properties.getApiKey()).retrieve()
					.body(SharadarMarketDataResponse.class);
			return response == null || response.data() == null ? List.of() : response.data().stream()
					.filter(row -> row.date() != null)
					.map(row -> new DailyPriceBar(row.date(), row.open(), row.high(), row.low(), row.close(),
							row.volume(), row.closeadj(), row.closeunadj(), row.lastupdated()))
					.toList();
		}
		catch (RestClientException exception) {
			ExternalApiErrorLogger.logFailure(log, "SHARADAR", table + "-prices", symbol, from, to,
					exception, properties.getApiKey());
			throw providerFailure(exception, table + " prices");
		}
	}

	private String priceTable(String providerEntityType) {
		if ("funds".equalsIgnoreCase(providerEntityType)) {
			return "funds";
		}
		if ("stocks".equalsIgnoreCase(providerEntityType)
				|| "fundamentals".equalsIgnoreCase(providerEntityType)) {
			return "stocks";
		}
		throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SHARADAR_PRICE_TABLE_UNSUPPORTED",
				"No Sharadar price table is configured for provider entity type " + providerEntityType);
	}

	@Override
	public List<FinancialReport> fetchReports(String symbol, LocalDate from) {
		ensureConfigured();
		try {
			SharadarFundamentalResponse response = restClient.get()
					.uri(builder -> builder.path("/data/fundamentals")
							.queryParam("format", "json").queryParam("ticker", symbol)
							.queryParam("from", from).queryParam("sort", "date.asc").build())
					.header("x-api-key", properties.getApiKey()).retrieve()
					.body(SharadarFundamentalResponse.class);
			return response == null || response.data() == null ? List.of() : response.data().stream()
					.filter(row -> row.date() != null && row.reportperiod() != null && row.dimension() != null)
					.map(this::toFinancialReport).toList();
		}
		catch (RestClientException exception) {
			ExternalApiErrorLogger.logFailure(log, "SHARADAR", "fundamentals", symbol, from, null,
					exception, properties.getApiKey());
			throw providerFailure(exception, "fundamentals");
		}
	}

	@Override
	public List<CorporateAction> fetchActions(String symbol, LocalDate from, LocalDate to) {
		ensureConfigured();
		try {
			SharadarActionResponse response = restClient.get()
					.uri(builder -> builder.path("/data/actions")
							.queryParam("format", "json").queryParam("ticker", symbol)
							.queryParam("from", from).queryParam("to", to).queryParam("sort", "date.asc")
							.build())
					.header("x-api-key", properties.getApiKey()).retrieve().body(SharadarActionResponse.class);
			return response == null || response.data() == null ? List.of() : response.data().stream()
					.filter(row -> row.date() != null && row.action() != null)
					.map(row -> new CorporateAction(row.date(), row.action(), row.name(), row.value(),
							row.contraticker(), row.contraname(), objectMapper.convertValue(row, RAW_TYPE)))
					.toList();
		}
		catch (RestClientException exception) {
			ExternalApiErrorLogger.logFailure(log, "SHARADAR", "corporate-actions", symbol, from, to,
					exception, properties.getApiKey());
			throw providerFailure(exception, "corporate actions");
		}
	}

	private FinancialReport toFinancialReport(SharadarFundamentalResponse.FundamentalRow row) {
		return new FinancialReport(row.dimension(), row.calendardate(), row.date(), row.reportperiod(),
				row.fiscalperiod(), row.lastupdated(), row.revenue(), row.gp(), row.opinc(), row.netinc(),
				row.ebitda(), row.fcf(), row.ncfo(), row.capex(), row.assets(), row.liabilities(), row.equity(),
				row.cashneq(), row.debt(), row.shareswadil(), row.epsdil(), row.grossmargin(), row.netmargin(),
				row.roe(), row.roa(), row.roic(), row.currentratio(), row.de(), row.pe(), row.evebitda(), row.ps(),
				row.marketcap(), objectMapper.convertValue(row, RAW_TYPE));
	}

	private void ensureConfigured() {
		if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHARADAR_NOT_CONFIGURED",
					"Sharadar is not configured. Set SHARADAR_API_KEY before running an analysis");
		}
	}

	private ApiException providerFailure(RestClientException exception, String dataset) {
		if (exception instanceof RestClientResponseException response) {
			int status = response.getStatusCode().value();
			if (status == 401) {
				return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHARADAR_AUTHENTICATION_FAILED",
						"Sharadar rejected the configured API key while fetching " + dataset);
			}
			if (status == 403) {
				return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHARADAR_ACCESS_DENIED",
						"Sharadar denied access to " + dataset + "; verify the subscription and API key");
			}
			if (status == 429) {
				return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHARADAR_RATE_LIMITED",
						"Sharadar rate limit was reached while fetching " + dataset);
			}
			if (status >= 500) {
				return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHARADAR_UNAVAILABLE",
						"Sharadar was unavailable while fetching " + dataset);
			}
		}
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SHARADAR_REQUEST_FAILED",
				"Sharadar could not provide " + dataset);
	}
}
