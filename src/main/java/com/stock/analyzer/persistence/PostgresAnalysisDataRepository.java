package com.stock.analyzer.persistence;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.stock.analyzer.fundamental.domain.FinancialReport;
import com.stock.analyzer.marketdata.domain.CorporateAction;
import com.stock.analyzer.marketdata.domain.DailyPriceBar;
import com.stock.analyzer.news.domain.NewsArticle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PostgresAnalysisDataRepository implements AnalysisDataRepository {

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public PostgresAnalysisDataRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public Optional<LocalDate> latestPriceDate(UUID instrumentId) {
		return optionalDate("SELECT MAX(price_date) FROM analyzer.daily_price_bar WHERE instrument_id = :id", instrumentId);
	}

	@Override
	public Optional<LocalDate> latestFundamentalProviderDate(UUID instrumentId) {
		return optionalDate("SELECT MAX(provider_date) FROM analyzer.financial_report WHERE instrument_id = :id", instrumentId);
	}

	@Override
	public Optional<LocalDate> latestActionDate(UUID instrumentId) {
		return optionalDate("SELECT MAX(action_date) FROM analyzer.corporate_action WHERE instrument_id = :id", instrumentId);
	}

	@Override
	public Optional<Instant> latestNewsUpdate(UUID instrumentId) {
		return jdbcClient.sql("""
				SELECT MAX(COALESCE(n.updated_at_provider, n.published_at))
				FROM analyzer.news_item n
				JOIN analyzer.news_item_instrument ni ON ni.news_item_id = n.id
				WHERE ni.instrument_id = :id
				""").param("id", instrumentId).query(OffsetDateTime.class).optional()
				.map(OffsetDateTime::toInstant);
	}

	@Override
	@Transactional
	public int savePrices(UUID instrumentId, List<DailyPriceBar> prices) {
		int written = 0;
		for (DailyPriceBar price : prices) {
			written += jdbcClient.sql("""
					INSERT INTO analyzer.daily_price_bar (
					    instrument_id, price_date, open_split_adjusted, high_split_adjusted,
					    low_split_adjusted, close_split_adjusted, volume_split_adjusted,
					    close_fully_adjusted, close_unadjusted, provider_last_updated, source_provider)
					VALUES (:id, :date, :open, :high, :low, :close, :volume, :adjusted, :unadjusted,
					        :lastUpdated, 'SHARADAR')
					ON CONFLICT (instrument_id, price_date) DO UPDATE SET
					    open_split_adjusted = EXCLUDED.open_split_adjusted,
					    high_split_adjusted = EXCLUDED.high_split_adjusted,
					    low_split_adjusted = EXCLUDED.low_split_adjusted,
					    close_split_adjusted = EXCLUDED.close_split_adjusted,
					    volume_split_adjusted = EXCLUDED.volume_split_adjusted,
					    close_fully_adjusted = EXCLUDED.close_fully_adjusted,
					    close_unadjusted = EXCLUDED.close_unadjusted,
					    provider_last_updated = EXCLUDED.provider_last_updated,
					    updated_at = CURRENT_TIMESTAMP
					""")
					.param("id", instrumentId).param("date", price.date()).param("open", price.open())
					.param("high", price.high()).param("low", price.low()).param("close", price.close())
					.param("volume", price.volume()).param("adjusted", price.fullyAdjustedClose())
					.param("unadjusted", price.unadjustedClose())
					.param("lastUpdated", price.providerLastUpdated()).update();
		}
		return written;
	}

	@Override
	@Transactional
	public int saveFinancialReports(UUID instrumentId, List<FinancialReport> reports) {
		int written = 0;
		for (FinancialReport report : reports) {
			written += jdbcClient.sql("""
					INSERT INTO analyzer.financial_report (
					    id, instrument_id, dimension, calendar_date, provider_date, report_period, fiscal_period,
					    provider_last_updated, revenue, gross_profit, operating_income, net_income, ebitda,
					    free_cash_flow, operating_cash_flow, capital_expenditure, assets, liabilities, equity,
					    cash_and_equivalents, debt, shares_diluted, eps_diluted, gross_margin, net_margin,
					    return_on_equity, return_on_assets, return_on_invested_capital, current_ratio,
					    debt_to_equity, price_earnings, enterprise_value_ebitda, price_sales, market_cap,
					    source_provider, raw_data)
					VALUES (
					    :rowId, :instrumentId, :dimension, :calendarDate, :providerDate, :reportPeriod,
					    :fiscalPeriod, :lastUpdated, :revenue, :grossProfit, :operatingIncome, :netIncome,
					    :ebitda, :freeCashFlow, :operatingCashFlow, :capitalExpenditure, :assets, :liabilities,
					    :equity, :cash, :debt, :dilutedShares, :dilutedEps, :grossMargin, :netMargin, :roe,
					    :roa, :roic, :currentRatio, :debtToEquity, :pe, :evEbitda, :priceSales, :marketCap,
					    'SHARADAR', CAST(:rawData AS jsonb))
					ON CONFLICT (instrument_id, dimension, provider_date, report_period, source_provider)
					DO UPDATE SET
					    calendar_date = EXCLUDED.calendar_date, fiscal_period = EXCLUDED.fiscal_period,
					    provider_last_updated = EXCLUDED.provider_last_updated, revenue = EXCLUDED.revenue,
					    gross_profit = EXCLUDED.gross_profit, operating_income = EXCLUDED.operating_income,
					    net_income = EXCLUDED.net_income, ebitda = EXCLUDED.ebitda,
					    free_cash_flow = EXCLUDED.free_cash_flow, operating_cash_flow = EXCLUDED.operating_cash_flow,
					    capital_expenditure = EXCLUDED.capital_expenditure, assets = EXCLUDED.assets,
					    liabilities = EXCLUDED.liabilities, equity = EXCLUDED.equity,
					    cash_and_equivalents = EXCLUDED.cash_and_equivalents, debt = EXCLUDED.debt,
					    shares_diluted = EXCLUDED.shares_diluted, eps_diluted = EXCLUDED.eps_diluted,
					    gross_margin = EXCLUDED.gross_margin, net_margin = EXCLUDED.net_margin,
					    return_on_equity = EXCLUDED.return_on_equity, return_on_assets = EXCLUDED.return_on_assets,
					    return_on_invested_capital = EXCLUDED.return_on_invested_capital,
					    current_ratio = EXCLUDED.current_ratio, debt_to_equity = EXCLUDED.debt_to_equity,
					    price_earnings = EXCLUDED.price_earnings,
					    enterprise_value_ebitda = EXCLUDED.enterprise_value_ebitda,
					    price_sales = EXCLUDED.price_sales, market_cap = EXCLUDED.market_cap,
					    raw_data = EXCLUDED.raw_data, updated_at = CURRENT_TIMESTAMP
					""")
					.param("rowId", UUID.randomUUID()).param("instrumentId", instrumentId)
					.param("dimension", report.dimension()).param("calendarDate", report.calendarDate())
					.param("providerDate", report.providerDate()).param("reportPeriod", report.reportPeriod())
					.param("fiscalPeriod", report.fiscalPeriod()).param("lastUpdated", report.providerLastUpdated())
					.param("revenue", report.revenue()).param("grossProfit", report.grossProfit())
					.param("operatingIncome", report.operatingIncome()).param("netIncome", report.netIncome())
					.param("ebitda", report.ebitda()).param("freeCashFlow", report.freeCashFlow())
					.param("operatingCashFlow", report.operatingCashFlow())
					.param("capitalExpenditure", report.capitalExpenditure()).param("assets", report.assets())
					.param("liabilities", report.liabilities()).param("equity", report.equity())
					.param("cash", report.cashAndEquivalents()).param("debt", report.debt())
					.param("dilutedShares", report.dilutedShares()).param("dilutedEps", report.dilutedEps())
					.param("grossMargin", report.grossMargin()).param("netMargin", report.netMargin())
					.param("roe", report.returnOnEquity()).param("roa", report.returnOnAssets())
					.param("roic", report.returnOnInvestedCapital()).param("currentRatio", report.currentRatio())
					.param("debtToEquity", report.debtToEquity()).param("pe", report.priceEarnings())
					.param("evEbitda", report.enterpriseValueEbitda()).param("priceSales", report.priceSales())
					.param("marketCap", report.marketCap()).param("rawData", json(report.rawData())).update();
		}
		return written;
	}

	@Override
	@Transactional
	public int saveCorporateActions(UUID instrumentId, String symbol, List<CorporateAction> actions) {
		int written = 0;
		for (CorporateAction action : actions) {
			written += jdbcClient.sql("""
					INSERT INTO analyzer.corporate_action (
					    id, instrument_id, action_date, action_type, value, contra_symbol, contra_name,
					    provider_record_key, source_provider, raw_data)
					VALUES (:id, :instrumentId, :date, :type, :value, :contraSymbol, :contraName,
					        :recordKey, 'SHARADAR', CAST(:rawData AS jsonb))
					ON CONFLICT (source_provider, provider_record_key) DO UPDATE SET
					    value = EXCLUDED.value, contra_symbol = EXCLUDED.contra_symbol,
					    contra_name = EXCLUDED.contra_name, raw_data = EXCLUDED.raw_data,
					    updated_at = CURRENT_TIMESTAMP
					""").param("id", UUID.randomUUID()).param("instrumentId", instrumentId)
					.param("date", action.date()).param("type", action.actionType()).param("value", action.value())
					.param("contraSymbol", action.contraSymbol()).param("contraName", action.contraName())
					.param("recordKey", action.providerRecordKey(symbol)).param("rawData", json(action.rawData()))
					.update();
		}
		return written;
	}

	@Override
	@Transactional
	public int saveNews(UUID instrumentId, List<NewsArticle> news) {
		int written = 0;
		for (NewsArticle article : news) {
			UUID newsId = jdbcClient.sql("""
					SELECT id FROM analyzer.news_item
					WHERE source_provider = 'BENZINGA' AND provider_article_id = :providerId
					""").param("providerId", article.providerArticleId()).query(UUID.class).optional()
					.orElseGet(UUID::randomUUID);
			written += jdbcClient.sql("""
					INSERT INTO analyzer.news_item (
					    id, source_provider, provider_article_id, published_at, updated_at_provider, title,
					    teaser, body, article_url, author, channels, tags, raw_data, content_hash)
					VALUES (:id, 'BENZINGA', :providerId, :published, :updated, :title, :teaser, :body,
					        :url, :author, CAST(:channels AS jsonb), CAST(:tags AS jsonb), CAST(:raw AS jsonb), :hash)
					ON CONFLICT (source_provider, provider_article_id) DO UPDATE SET
					    updated_at_provider = EXCLUDED.updated_at_provider, title = EXCLUDED.title,
					    teaser = EXCLUDED.teaser, body = EXCLUDED.body, article_url = EXCLUDED.article_url,
					    author = EXCLUDED.author, channels = EXCLUDED.channels, tags = EXCLUDED.tags,
					    raw_data = EXCLUDED.raw_data, content_hash = EXCLUDED.content_hash,
					    updated_at = CURRENT_TIMESTAMP
					""").param("id", newsId).param("providerId", article.providerArticleId())
					.param("published", offset(article.publishedAt())).param("updated", offset(article.updatedAt()))
					.param("title", article.title()).param("teaser", article.teaser()).param("body", article.body())
					.param("url", article.url()).param("author", article.author())
					.param("channels", json(article.channels())).param("tags", json(article.tags()))
					.param("raw", json(article.rawData())).param("hash", hash(article.title() + "|" + article.teaser()))
					.update();
			jdbcClient.sql("""
					INSERT INTO analyzer.news_item_instrument (news_item_id, instrument_id, provider_symbol)
					VALUES (:newsId, :instrumentId, :symbol)
					ON CONFLICT (news_item_id, instrument_id) DO NOTHING
					""").param("newsId", newsId).param("instrumentId", instrumentId)
					.param("symbol", article.symbols().isEmpty() ? null : article.symbols().getFirst()).update();
		}
		return written;
	}

	@Override
	public List<DailyPriceBar> loadPrices(UUID instrumentId, LocalDate throughDate, int limit) {
		return jdbcClient.sql("""
				SELECT * FROM (
				    SELECT price_date, open_split_adjusted, high_split_adjusted, low_split_adjusted,
				           close_split_adjusted, volume_split_adjusted, close_fully_adjusted,
				           close_unadjusted, provider_last_updated
				    FROM analyzer.daily_price_bar
				    WHERE instrument_id = :id AND price_date <= :throughDate
				    ORDER BY price_date DESC LIMIT :limit
				) recent ORDER BY price_date ASC
				""").param("id", instrumentId).param("throughDate", throughDate).param("limit", limit)
				.query(this::mapPrice).list();
	}

	@Override
	public List<FinancialReport> loadFinancialReports(UUID instrumentId, LocalDate availableThrough) {
		return jdbcClient.sql("""
				SELECT * FROM analyzer.financial_report
				WHERE instrument_id = :id AND provider_date <= :throughDate
				  AND dimension IN ('ART', 'ARQ', 'ARY')
				ORDER BY provider_date DESC, report_period DESC
				""").param("id", instrumentId).param("throughDate", availableThrough)
				.query(this::mapFinancialReport).list();
	}

	@Override
	public List<NewsArticle> loadNews(UUID instrumentId, Instant publishedAfter) {
		return jdbcClient.sql("""
				SELECT n.provider_article_id, n.published_at, n.updated_at_provider, n.title, n.teaser,
				       n.body, n.article_url, n.author, n.channels::text, n.tags::text, n.raw_data::text
				FROM analyzer.news_item n
				JOIN analyzer.news_item_instrument ni ON ni.news_item_id = n.id
				WHERE ni.instrument_id = :id AND n.published_at >= :publishedAfter
				ORDER BY n.published_at DESC
				""").param("id", instrumentId).param("publishedAfter", offset(publishedAfter))
				.query(this::mapNews).list();
	}

	@Override
	public List<PeerSnapshot> loadPeers(UUID instrumentId, String sector, String industry, LocalDate availableThrough) {
		if (industry != null && !industry.isBlank()) {
			return peerQuery("i.industry = :classification", instrumentId, industry, availableThrough);
		}
		if (sector != null && !sector.isBlank()) {
			return peerQuery("i.sector = :classification", instrumentId, sector, availableThrough);
		}
		return List.of();
	}

	private List<PeerSnapshot> peerQuery(String classificationPredicate, UUID instrumentId,
			String classification, LocalDate availableThrough) {
		String sql = """
				SELECT DISTINCT ON (i.id) i.current_symbol, i.sector, i.industry, f.gross_margin,
				       f.net_margin, f.return_on_equity, f.price_earnings,
				       f.enterprise_value_ebitda, f.market_cap
				FROM analyzer.instrument i
				JOIN analyzer.financial_report f ON f.instrument_id = i.id
				WHERE i.id <> :id AND i.active = TRUE AND f.dimension = 'ART'
				  AND f.provider_date <= :throughDate
				  AND %s
				ORDER BY i.id, f.provider_date DESC, f.report_period DESC
				LIMIT 25
				""".formatted(classificationPredicate);
		return jdbcClient.sql(sql).param("id", instrumentId).param("throughDate", availableThrough)
				.param("classification", classification)
				.query((rs, row) -> new PeerSnapshot(rs.getString("current_symbol"), rs.getString("sector"),
						rs.getString("industry"), rs.getBigDecimal("gross_margin"), rs.getBigDecimal("net_margin"),
						rs.getBigDecimal("return_on_equity"), rs.getBigDecimal("price_earnings"),
						rs.getBigDecimal("enterprise_value_ebitda"), rs.getBigDecimal("market_cap"))).list();
	}

	@Override
	public void updateSyncState(UUID instrumentId, String provider, String dataset, LocalDate lastObservation,
			LocalDate lastProviderUpdate, String status, String error) {
		jdbcClient.sql("""
				INSERT INTO analyzer.provider_sync_state (
				    instrument_id, provider_key, dataset, last_observation_date, last_provider_update,
				    last_success_at, status, last_error)
				VALUES (:id, :provider, :dataset, :observation, :providerUpdate,
				        CASE WHEN :status = 'SUCCESS' THEN CURRENT_TIMESTAMP ELSE NULL END, :status, :error)
				ON CONFLICT (instrument_id, provider_key, dataset) DO UPDATE SET
				    last_observation_date = COALESCE(EXCLUDED.last_observation_date,
				        analyzer.provider_sync_state.last_observation_date),
				    last_provider_update = COALESCE(EXCLUDED.last_provider_update,
				        analyzer.provider_sync_state.last_provider_update),
				    last_success_at = CASE WHEN EXCLUDED.status = 'SUCCESS' THEN CURRENT_TIMESTAMP
				        ELSE analyzer.provider_sync_state.last_success_at END,
				    status = EXCLUDED.status, last_error = EXCLUDED.last_error, updated_at = CURRENT_TIMESTAMP
				""").param("id", instrumentId).param("provider", provider).param("dataset", dataset)
				.param("observation", lastObservation).param("providerUpdate", lastProviderUpdate)
				.param("status", status).param("error", error).update();
	}

	@Override
	public void recordIngestion(UUID instrumentId, String provider, String dataset, LocalDate from, LocalDate to,
			String status, int received, int written, long durationMs, String errorCode, String errorMessage) {
		jdbcClient.sql("""
				INSERT INTO analyzer.ingestion_run (
				    id, instrument_id, provider_key, dataset, requested_from, requested_to, status,
				    records_received, records_written, completed_at, duration_ms, error_code, error_message)
				VALUES (:runId, :instrumentId, :provider, :dataset, :from, :to, :status,
				        :received, :written, CURRENT_TIMESTAMP, :duration, :errorCode, :errorMessage)
				""").param("runId", UUID.randomUUID()).param("instrumentId", instrumentId)
				.param("provider", provider).param("dataset", dataset).param("from", from).param("to", to)
				.param("status", status).param("received", received).param("written", written)
				.param("duration", durationMs).param("errorCode", errorCode).param("errorMessage", errorMessage)
				.update();
	}

	private Optional<LocalDate> optionalDate(String sql, UUID instrumentId) {
		return jdbcClient.sql(sql).param("id", instrumentId).query(LocalDate.class).optional();
	}

	private DailyPriceBar mapPrice(ResultSet rs, int row) throws SQLException {
		return new DailyPriceBar(localDate(rs, "price_date"), rs.getBigDecimal("open_split_adjusted"),
				rs.getBigDecimal("high_split_adjusted"), rs.getBigDecimal("low_split_adjusted"),
				rs.getBigDecimal("close_split_adjusted"), rs.getBigDecimal("volume_split_adjusted"),
				rs.getBigDecimal("close_fully_adjusted"), rs.getBigDecimal("close_unadjusted"),
				localDate(rs, "provider_last_updated"));
	}

	private FinancialReport mapFinancialReport(ResultSet rs, int row) throws SQLException {
		return new FinancialReport(rs.getString("dimension"), localDate(rs, "calendar_date"),
				localDate(rs, "provider_date"), localDate(rs, "report_period"), rs.getString("fiscal_period"),
				localDate(rs, "provider_last_updated"), rs.getBigDecimal("revenue"),
				rs.getBigDecimal("gross_profit"), rs.getBigDecimal("operating_income"),
				rs.getBigDecimal("net_income"), rs.getBigDecimal("ebitda"), rs.getBigDecimal("free_cash_flow"),
				rs.getBigDecimal("operating_cash_flow"), rs.getBigDecimal("capital_expenditure"),
				rs.getBigDecimal("assets"), rs.getBigDecimal("liabilities"), rs.getBigDecimal("equity"),
				rs.getBigDecimal("cash_and_equivalents"), rs.getBigDecimal("debt"),
				rs.getBigDecimal("shares_diluted"), rs.getBigDecimal("eps_diluted"),
				rs.getBigDecimal("gross_margin"), rs.getBigDecimal("net_margin"),
				rs.getBigDecimal("return_on_equity"), rs.getBigDecimal("return_on_assets"),
				rs.getBigDecimal("return_on_invested_capital"), rs.getBigDecimal("current_ratio"),
				rs.getBigDecimal("debt_to_equity"), rs.getBigDecimal("price_earnings"),
				rs.getBigDecimal("enterprise_value_ebitda"), rs.getBigDecimal("price_sales"),
				rs.getBigDecimal("market_cap"), objectMapper.readValue(rs.getString("raw_data"), Object.class) instanceof java.util.Map<?, ?> map
						? map.entrySet().stream().collect(java.util.stream.Collectors.toMap(e -> String.valueOf(e.getKey()), java.util.Map.Entry::getValue))
						: java.util.Map.of());
	}

	private NewsArticle mapNews(ResultSet rs, int row) throws SQLException {
		return new NewsArticle(rs.getString("provider_article_id"), rs.getTimestamp("published_at").toInstant(),
				instant(rs, "updated_at_provider"), rs.getString("title"), rs.getString("teaser"),
				rs.getString("body"), rs.getString("article_url"), rs.getString("author"), List.of(),
				readStringList(rs.getString("channels")), readStringList(rs.getString("tags")),
				readMap(rs.getString("raw_data")));
	}

	private LocalDate localDate(ResultSet rs, String column) throws SQLException {
		Date value = rs.getDate(column);
		return value == null ? null : value.toLocalDate();
	}

	private Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private OffsetDateTime offset(Instant value) {
		return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Could not serialize analysis data", exception);
		}
	}

	private List<String> readStringList(String value) {
		try {
			return objectMapper.readValue(value, new tools.jackson.core.type.TypeReference<>() { });
		}
		catch (JacksonException exception) {
			return List.of();
		}
	}

	private java.util.Map<String, Object> readMap(String value) {
		try {
			return objectMapper.readValue(value, new tools.jackson.core.type.TypeReference<>() { });
		}
		catch (JacksonException exception) {
			return java.util.Map.of();
		}
	}

	private String hash(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}
}
