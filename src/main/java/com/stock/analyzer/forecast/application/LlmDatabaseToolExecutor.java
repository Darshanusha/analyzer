package com.stock.analyzer.forecast.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Executes a fixed, read-only tool catalogue. Model-provided SQL is never accepted. */
@Component
public class LlmDatabaseToolExecutor {
	private final JdbcClient jdbc;
	private final ObjectMapper mapper;

	public LlmDatabaseToolExecutor(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	public ToolResult execute(String name, UUID instrumentId, LocalDate cutoff, int requestedLimit) {
		int limit = Math.max(1, Math.min(requestedLimit, 120));
		List<Map<String, Object>> rows = switch (name) {
			case "get_available_stock_data" -> jdbc.sql("""
					SELECT (SELECT COUNT(*) FROM analyzer.daily_price_bar WHERE instrument_id=:id AND price_date<=:cutoff) price_rows,
					       (SELECT COUNT(*) FROM analyzer.financial_report WHERE instrument_id=:id AND provider_date<=:cutoff) fundamental_rows,
					       (SELECT COUNT(*) FROM analyzer.news_item_instrument ni JOIN analyzer.news_item n ON n.id=ni.news_item_id
					        WHERE ni.instrument_id=:id AND n.published_at < (CAST(:cutoff AS date) + INTERVAL '1 day')) news_rows
					""").param("id", instrumentId).param("cutoff", cutoff).query().listOfRows();
			case "get_price_history" -> jdbc.sql("""
					SELECT price_date, open_split_adjusted AS open, high_split_adjusted AS high,
					       low_split_adjusted AS low, close_split_adjusted AS close, volume_split_adjusted AS volume,
					       close_fully_adjusted,
					       CASE WHEN close_fully_adjusted IS NOT NULL AND close_split_adjusted <> 0
					            THEN high_split_adjusted * close_fully_adjusted / close_split_adjusted
					            ELSE high_split_adjusted END AS high_fully_adjusted,
					       CASE WHEN close_fully_adjusted IS NOT NULL AND close_split_adjusted <> 0
					            THEN low_split_adjusted * close_fully_adjusted / close_split_adjusted
					            ELSE low_split_adjusted END AS low_fully_adjusted
					FROM analyzer.daily_price_bar WHERE instrument_id=:id AND price_date<=:cutoff
					ORDER BY price_date DESC LIMIT :limit
					""").param("id", instrumentId).param("cutoff", cutoff).param("limit", limit).query().listOfRows();
			case "get_fundamental_history" -> jdbc.sql("""
					SELECT dimension, provider_date, report_period, fiscal_period, revenue, net_income, free_cash_flow,
					       gross_margin, net_margin, return_on_equity, debt_to_equity, price_earnings,
					       enterprise_value_ebitda, market_cap
					FROM analyzer.financial_report WHERE instrument_id=:id AND provider_date<=:cutoff
					  AND dimension IN ('ART','ARQ','ARY') ORDER BY provider_date DESC, report_period DESC LIMIT :limit
					""").param("id", instrumentId).param("cutoff", cutoff).param("limit", Math.min(limit, 20)).query().listOfRows();
			case "get_recent_news" -> jdbc.sql("""
					SELECT n.published_at, n.title, n.teaser, n.article_url, n.source_provider
					FROM analyzer.news_item n JOIN analyzer.news_item_instrument ni ON ni.news_item_id=n.id
					WHERE ni.instrument_id=:id AND n.published_at < (CAST(:cutoff AS date) + INTERVAL '1 day')
					ORDER BY n.published_at DESC LIMIT :limit
					""").param("id", instrumentId).param("cutoff", cutoff).param("limit", Math.min(limit, 30)).query().listOfRows();
			case "get_market_context" -> benchmark("SPY", cutoff, Math.min(limit, 60));
			case "get_sector_context" -> sectorBenchmark(instrumentId, cutoff, Math.min(limit, 60));
			default -> throw new IllegalArgumentException("Unsupported database tool: " + name);
		};
		return new ToolResult(json(Map.of("tool", name, "cutoffDate", cutoff, "rows", rows)), rows.size());
	}

	private List<Map<String, Object>> sectorBenchmark(UUID instrumentId, LocalDate cutoff, int limit) {
		String sector = jdbc.sql("SELECT COALESCE(sector,'') FROM analyzer.instrument WHERE id=:id")
				.param("id", instrumentId).query(String.class).optional().orElse("").toLowerCase();
		String symbol = Map.ofEntries(Map.entry("technology", "XLK"), Map.entry("financial services", "XLF"),
				Map.entry("healthcare", "XLV"), Map.entry("consumer cyclical", "XLY"),
				Map.entry("consumer defensive", "XLP"), Map.entry("industrials", "XLI"),
				Map.entry("energy", "XLE"), Map.entry("basic materials", "XLB"),
				Map.entry("utilities", "XLU"), Map.entry("real estate", "XLRE"),
				Map.entry("communication services", "XLC")).getOrDefault(sector, "SPY");
		return benchmark(symbol, cutoff, limit);
	}

	private List<Map<String, Object>> benchmark(String symbol, LocalDate cutoff, int limit) {
		return jdbc.sql("""
				SELECT i.current_symbol AS symbol, p.price_date,
				       COALESCE(p.close_fully_adjusted, p.close_split_adjusted) AS adjusted_close
				FROM analyzer.instrument i JOIN analyzer.daily_price_bar p ON p.instrument_id=i.id
				WHERE i.current_symbol=:symbol AND p.price_date<=:cutoff
				ORDER BY p.price_date DESC LIMIT :limit
				""").param("symbol", symbol).param("cutoff", cutoff).param("limit", limit).query().listOfRows();
	}

	private String json(Object value) {
		try { return mapper.writeValueAsString(value); }
		catch (JacksonException e) { throw new IllegalStateException("Tool result serialization failed", e); }
	}

	public record ToolResult(String json, int resultCount) { }
}
