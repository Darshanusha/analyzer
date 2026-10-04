CREATE TABLE analyzer.data_provider (
    provider_key VARCHAR(50) PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO analyzer.data_provider (provider_key, display_name)
VALUES ('SHARADAR', 'Sharadar');

CREATE TABLE analyzer.instrument (
    id UUID PRIMARY KEY,
    asset_type VARCHAR(30) NOT NULL,
    current_symbol VARCHAR(32) NOT NULL,
    name VARCHAR(255) NOT NULL,
    primary_exchange VARCHAR(50),
    currency VARCHAR(3),
    active BOOLEAN NOT NULL,
    sector VARCHAR(150),
    industry VARCHAR(150),
    sic_code VARCHAR(20),
    sic_sector VARCHAR(150),
    sic_industry VARCHAR(150),
    fama_industry VARCHAR(150),
    composite_figi VARCHAR(50),
    location VARCHAR(255),
    sec_filings_url TEXT,
    company_url TEXT,
    first_price_date DATE,
    last_price_date DATE,
    first_fundamental_quarter DATE,
    last_fundamental_quarter DATE,
    provider_last_updated DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX ux_instrument_active_symbol
    ON analyzer.instrument (current_symbol)
    WHERE active = TRUE;

CREATE INDEX ix_instrument_sector_industry
    ON analyzer.instrument (sector, industry);

CREATE TABLE analyzer.provider_instrument_mapping (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL REFERENCES analyzer.instrument (id),
    provider_key VARCHAR(50) NOT NULL REFERENCES analyzer.data_provider (provider_key),
    provider_entity_type VARCHAR(50) NOT NULL,
    provider_identifier VARCHAR(100) NOT NULL,
    provider_symbol VARCHAR(32) NOT NULL,
    valid_from DATE,
    valid_to DATE,
    raw_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_provider_instrument_identity
        UNIQUE (provider_key, provider_entity_type, provider_identifier)
);

CREATE INDEX ix_provider_mapping_symbol
    ON analyzer.provider_instrument_mapping (provider_key, provider_symbol)
    WHERE valid_to IS NULL;

CREATE TABLE analyzer.symbol_history (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL REFERENCES analyzer.instrument (id),
    symbol VARCHAR(32) NOT NULL,
    exchange VARCHAR(50),
    valid_from DATE,
    valid_to DATE,
    is_primary BOOLEAN NOT NULL DEFAULT TRUE,
    source_provider VARCHAR(50) NOT NULL REFERENCES analyzer.data_provider (provider_key),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_symbol_history_lookup
    ON analyzer.symbol_history (symbol, valid_from, valid_to);
