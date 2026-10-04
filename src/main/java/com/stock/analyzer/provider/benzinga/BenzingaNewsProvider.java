package com.stock.analyzer.provider.benzinga;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.stock.analyzer.api.error.ApiException;
import com.stock.analyzer.news.application.NewsProvider;
import com.stock.analyzer.news.domain.NewsArticle;
import com.stock.analyzer.provider.ExternalApiErrorLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class BenzingaNewsProvider implements NewsProvider {

	private static final TypeReference<Map<String, Object>> RAW_TYPE = new TypeReference<>() { };
	private static final Logger log = LoggerFactory.getLogger(BenzingaNewsProvider.class);

	private final BenzingaProperties properties;
	private final ObjectMapper objectMapper;
	private final RestClient restClient;

	public BenzingaNewsProvider(BenzingaProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.restClient = RestClient.create(properties.getBaseUrl().toString());
	}

	@Override
	public boolean isConfigured() {
		return properties.getApiKey() != null && !properties.getApiKey().isBlank();
	}

	@Override
	public List<NewsArticle> fetchNews(String symbol, Instant updatedSince) {
		if (!isConfigured()) {
			return List.of();
		}
		String body;
		try {
			body = restClient.get().uri(builder -> builder.path("/api/v2/news")
					.queryParam("token", properties.getApiKey()).queryParam("tickers", symbol)
					.queryParam("updatedSince", updatedSince.toEpochMilli()).queryParam("pageSize", 100)
					.queryParam("displayOutput", "full").build()).retrieve().body(String.class);
		}
		catch (RestClientResponseException exception) {
			ExternalApiErrorLogger.logFailure(log, "BENZINGA", "news", symbol, updatedSince, null,
					exception, properties.getApiKey());
			throw providerFailure(exception);
		}
		catch (RestClientException exception) {
			ExternalApiErrorLogger.logFailure(log, "BENZINGA", "news", symbol, updatedSince, null,
					exception, properties.getApiKey());
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BENZINGA_UNAVAILABLE",
					"Benzinga news could not be retrieved");
		}
		try {
			return parse(body);
		}
		catch (JacksonException exception) {
			ExternalApiErrorLogger.logInvalidResponse(log, "BENZINGA", "news", symbol, body,
					exception, properties.getApiKey());
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BENZINGA_INVALID_RESPONSE",
					"Benzinga returned a response that could not be parsed");
		}
	}

	private ApiException providerFailure(RestClientResponseException exception) {
		int status = exception.getStatusCode().value();
		if (status == 401 || status == 403) {
			return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BENZINGA_ACCESS_DENIED",
					"Benzinga rejected the configured API access");
		}
		if (status == 429) {
			return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BENZINGA_RATE_LIMITED",
					"Benzinga rate limit was reached; retry later");
		}
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "BENZINGA_REQUEST_FAILED",
				"Benzinga returned an unsuccessful response");
	}

	private List<NewsArticle> parse(String body) throws JacksonException {
		if (body == null || body.isBlank()) {
			return List.of();
		}
		JsonNode root = objectMapper.readTree(body);
		JsonNode articles = root.isArray() ? root : root.path("data");
		if (!articles.isArray()) {
			articles = root.path("news");
		}
		List<NewsArticle> result = new ArrayList<>();
		for (JsonNode node : articles) {
			String id = text(node, "id");
			String title = text(node, "title");
			Instant published = instant(node, "created");
			if (id == null || title == null || published == null) {
				continue;
			}
			result.add(new NewsArticle(id, published, instant(node, "updated"), title, text(node, "teaser"),
					text(node, "body"), text(node, "url"), text(node, "author"), names(node.path("stocks")),
					names(node.path("channels")), names(node.path("tags")), objectMapper.convertValue(node, RAW_TYPE)));
		}
		return result;
	}

	private List<String> names(JsonNode nodes) {
		if (!nodes.isArray()) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (JsonNode node : nodes) {
			String value = node.isTextual() ? node.asText() : text(node, "name");
			if (value == null) {
				value = text(node, "ticker");
			}
			if (value != null && !value.isBlank()) {
				values.add(value);
			}
		}
		return values;
	}

	private String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isMissingNode() || value.isNull() ? null : value.asText();
	}

	private Instant instant(JsonNode node, String field) {
		String value = text(node, field);
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(value).toInstant();
		}
		catch (DateTimeParseException ignored) {
			try {
				return java.time.LocalDateTime.parse(value.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
			}
			catch (DateTimeParseException invalid) {
				return null;
			}
		}
	}
}
