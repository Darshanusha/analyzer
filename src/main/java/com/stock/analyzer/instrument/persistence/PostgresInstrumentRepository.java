package com.stock.analyzer.instrument.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import com.stock.analyzer.instrument.application.InstrumentRepository;
import com.stock.analyzer.instrument.domain.Instrument;
import com.stock.analyzer.instrument.domain.InstrumentProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
public class PostgresInstrumentRepository implements InstrumentRepository {

	private static final String FIND_ACTIVE_BY_SYMBOL = """
			SELECT i.id, i.current_symbol, i.name, i.primary_exchange, i.currency, i.active,
			       i.sector, i.industry, i.provider_last_updated, pim.provider_identifier,
			       pim.provider_entity_type
			FROM analyzer.instrument i
			JOIN analyzer.provider_instrument_mapping pim ON pim.instrument_id = i.id
			WHERE i.active = TRUE
			  AND i.current_symbol = :symbol
			  AND pim.provider_key = 'SHARADAR'
			  AND pim.valid_to IS NULL
			ORDER BY CASE pim.provider_entity_type WHEN 'stocks' THEN 0 WHEN 'fundamentals' THEN 1 ELSE 2 END
			LIMIT 1
			""";

	private final JdbcClient jdbcClient;
	private final ObjectMapper objectMapper;

	public PostgresInstrumentRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
		this.jdbcClient = jdbcClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public Optional<Instrument> findActiveBySymbol(String symbol) {
		return jdbcClient.sql(FIND_ACTIVE_BY_SYMBOL)
				.param("symbol", symbol)
				.query(this::mapInstrument)
				.optional();
	}

	@Override
	@Transactional
	public Instrument upsert(InstrumentProfile profile) {
		UUID instrumentId = findInstrumentId(profile).orElseGet(UUID::randomUUID);
		upsertInstrument(instrumentId, profile);
		upsertProviderMapping(instrumentId, profile);
		insertCurrentSymbolIfMissing(instrumentId, profile);
		return new Instrument(
				instrumentId,
				profile.symbol(),
				profile.name(),
				profile.exchange(),
				profile.currency(),
				profile.active(),
				profile.sector(),
				profile.industry(),
				profile.permanentProviderId(),
				profile.providerEntityType(),
				profile.lastUpdated());
	}

	private Optional<UUID> findInstrumentId(InstrumentProfile profile) {
		Optional<UUID> mappedId = jdbcClient.sql("""
				SELECT instrument_id
				FROM analyzer.provider_instrument_mapping
				WHERE provider_key = :providerKey
				  AND provider_identifier = :providerIdentifier
				LIMIT 1
				""")
				.param("providerKey", profile.provider())
				.param("providerIdentifier", profile.permanentProviderId())
				.query(UUID.class)
				.optional();
		if (mappedId.isPresent()) {
			return mappedId;
		}
		return jdbcClient.sql("""
				SELECT id FROM analyzer.instrument
				WHERE current_symbol = :symbol AND active = TRUE
				LIMIT 1
				""")
				.param("symbol", profile.symbol())
				.query(UUID.class)
				.optional();
	}

	private void upsertInstrument(UUID instrumentId, InstrumentProfile profile) {
		jdbcClient.sql("""
				INSERT INTO analyzer.instrument (
				    id, asset_type, current_symbol, name, primary_exchange, currency, active,
				    sector, industry, sic_code, sic_sector, sic_industry, fama_industry,
				    composite_figi, location, sec_filings_url, company_url, first_price_date,
				    last_price_date, first_fundamental_quarter, last_fundamental_quarter,
				    provider_last_updated)
				VALUES (
				    :id, :assetType, :symbol, :name, :exchange, :currency, :active,
				    :sector, :industry, :sicCode, :sicSector, :sicIndustry, :famaIndustry,
				    :figi, :location, :secFilingsUrl, :companyUrl, :firstPriceDate,
				    :lastPriceDate, :firstQuarter, :lastQuarter, :lastUpdated)
				ON CONFLICT (id) DO UPDATE SET
				    asset_type = EXCLUDED.asset_type,
				    current_symbol = EXCLUDED.current_symbol,
				    name = EXCLUDED.name,
				    primary_exchange = EXCLUDED.primary_exchange,
				    currency = EXCLUDED.currency,
				    active = EXCLUDED.active,
				    sector = EXCLUDED.sector,
				    industry = EXCLUDED.industry,
				    sic_code = EXCLUDED.sic_code,
				    sic_sector = EXCLUDED.sic_sector,
				    sic_industry = EXCLUDED.sic_industry,
				    fama_industry = EXCLUDED.fama_industry,
				    composite_figi = EXCLUDED.composite_figi,
				    location = EXCLUDED.location,
				    sec_filings_url = EXCLUDED.sec_filings_url,
				    company_url = EXCLUDED.company_url,
				    first_price_date = EXCLUDED.first_price_date,
				    last_price_date = EXCLUDED.last_price_date,
				    first_fundamental_quarter = EXCLUDED.first_fundamental_quarter,
				    last_fundamental_quarter = EXCLUDED.last_fundamental_quarter,
				    provider_last_updated = EXCLUDED.provider_last_updated,
				    updated_at = CURRENT_TIMESTAMP
				""")
				.param("id", instrumentId)
				.param("assetType", assetType(profile.providerEntityType()))
				.param("symbol", profile.symbol())
				.param("name", profile.name())
				.param("exchange", profile.exchange())
				.param("currency", profile.currency())
				.param("active", profile.active())
				.param("sector", profile.sector())
				.param("industry", profile.industry())
				.param("sicCode", profile.sicCode())
				.param("sicSector", profile.sicSector())
				.param("sicIndustry", profile.sicIndustry())
				.param("famaIndustry", profile.famaIndustry())
				.param("figi", profile.compositeFigi())
				.param("location", profile.location())
				.param("secFilingsUrl", profile.secFilingsUrl())
				.param("companyUrl", profile.companyUrl())
				.param("firstPriceDate", profile.firstPriceDate())
				.param("lastPriceDate", profile.lastPriceDate())
				.param("firstQuarter", profile.firstQuarter())
				.param("lastQuarter", profile.lastQuarter())
				.param("lastUpdated", profile.lastUpdated())
				.update();
	}

	private void upsertProviderMapping(UUID instrumentId, InstrumentProfile profile) {
		jdbcClient.sql("""
				INSERT INTO analyzer.provider_instrument_mapping (
				    id, instrument_id, provider_key, provider_entity_type, provider_identifier,
				    provider_symbol, valid_from, raw_metadata)
				VALUES (
				    :id, :instrumentId, :providerKey, :entityType, :providerIdentifier,
				    :providerSymbol, :validFrom, CAST(:rawMetadata AS jsonb))
				ON CONFLICT (provider_key, provider_entity_type, provider_identifier) DO UPDATE SET
				    instrument_id = EXCLUDED.instrument_id,
				    provider_symbol = EXCLUDED.provider_symbol,
				    valid_from = COALESCE(analyzer.provider_instrument_mapping.valid_from, EXCLUDED.valid_from),
				    valid_to = NULL,
				    raw_metadata = EXCLUDED.raw_metadata,
				    updated_at = CURRENT_TIMESTAMP
				""")
				.param("id", UUID.randomUUID())
				.param("instrumentId", instrumentId)
				.param("providerKey", profile.provider())
				.param("entityType", profile.providerEntityType())
				.param("providerIdentifier", profile.permanentProviderId())
				.param("providerSymbol", profile.symbol())
				.param("validFrom", profile.firstAdded())
				.param("rawMetadata", serializeRawMetadata(profile))
				.update();
	}

	private void insertCurrentSymbolIfMissing(UUID instrumentId, InstrumentProfile profile) {
		jdbcClient.sql("""
				INSERT INTO analyzer.symbol_history (
				    id, instrument_id, symbol, exchange, valid_from, is_primary, source_provider)
				SELECT :id, :instrumentId, :symbol, :exchange, :validFrom, TRUE, :providerKey
				WHERE NOT EXISTS (
				    SELECT 1 FROM analyzer.symbol_history
				    WHERE instrument_id = :instrumentId
				      AND symbol = :symbol
				      AND valid_to IS NULL
				)
				""")
				.param("id", UUID.randomUUID())
				.param("instrumentId", instrumentId)
				.param("symbol", profile.symbol())
				.param("exchange", profile.exchange())
				.param("validFrom", profile.firstAdded())
				.param("providerKey", profile.provider())
				.update();
	}

	private String serializeRawMetadata(InstrumentProfile profile) {
		try {
			return objectMapper.writeValueAsString(profile.rawMetadata());
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Could not serialize provider instrument metadata", exception);
		}
	}

	private Instrument mapInstrument(ResultSet resultSet, int rowNumber) throws SQLException {
		return new Instrument(
				resultSet.getObject("id", UUID.class),
				resultSet.getString("current_symbol"),
				resultSet.getString("name"),
				resultSet.getString("primary_exchange"),
				resultSet.getString("currency"),
				resultSet.getBoolean("active"),
				resultSet.getString("sector"),
				resultSet.getString("industry"),
				resultSet.getString("provider_identifier"),
				resultSet.getString("provider_entity_type"),
				toLocalDate(resultSet.getDate("provider_last_updated")));
	}

	private String assetType(String providerEntityType) {
		return "funds".equalsIgnoreCase(providerEntityType) ? "FUND" : "EQUITY";
	}

	private java.time.LocalDate toLocalDate(Date date) {
		return date == null ? null : date.toLocalDate();
	}
}
