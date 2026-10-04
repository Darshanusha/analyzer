INSERT INTO analyzer.data_provider (provider_key, display_name)
VALUES ('BENZINGA', 'Benzinga')
ON CONFLICT (provider_key) DO NOTHING;

CREATE TABLE analyzer.ingestion_run (
    id UUID PRIMARY KEY,
    instrument_id UUID REFERENCES analyzer.instrument (id),
    provider_key VARCHAR(50) NOT NULL REFERENCES analyzer.data_provider (provider_key),
    dataset VARCHAR(50) NOT NULL,
    requested_from DATE,
    requested_to DATE,
    status VARCHAR(30) NOT NULL,
    records_received INTEGER NOT NULL DEFAULT 0,
    records_written INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT,
    error_code VARCHAR(100),
    error_message TEXT,
    trace_id VARCHAR(64)
);

CREATE INDEX ix_ingestion_run_instrument
    ON analyzer.ingestion_run (instrument_id, dataset, started_at DESC);

CREATE TABLE analyzer.provider_sync_state (
    instrument_id UUID NOT NULL REFERENCES analyzer.instrument (id),
    provider_key VARCHAR(50) NOT NULL REFERENCES analyzer.data_provider (provider_key),
    dataset VARCHAR(50) NOT NULL,
    last_observation_date DATE,
    last_provider_update DATE,
    last_success_at TIMESTAMPTZ,
    status VARCHAR(30) NOT NULL,
    last_error TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (instrument_id, provider_key, dataset)
);

CREATE TABLE analyzer.daily_price_bar (
    instrument_id UUID NOT NULL REFERENCES analyzer.instrument (id),
    price_date DATE NOT NULL,
    open_split_adjusted NUMERIC(22, 8),
    high_split_adjusted NUMERIC(22, 8),
    low_split_adjusted NUMERIC(22, 8),
    close_split_adjusted NUMERIC(22, 8),
    volume_split_adjusted NUMERIC(28, 4),
    close_fully_adjusted NUMERIC(22, 8),
    close_unadjusted NUMERIC(22, 8),
    provider_last_updated DATE,
    source_provider VARCHAR(50) NOT NULL REFERENCES analyzer.data_provider (provider_key),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (instrument_id, price_date),
    CONSTRAINT ck_price_bar_high_low CHECK (
        high_split_adjusted IS NULL OR low_split_adjusted IS NULL OR high_split_adjusted >= low_split_adjusted
    )
);

CREATE INDEX ix_daily_price_bar_date ON analyzer.daily_price_bar (price_date DESC);

CREATE TABLE analyzer.corporate_action (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL REFERENCES analyzer.instrument (id),
    action_date DATE NOT NULL,
    action_type VARCHAR(80) NOT NULL,
    value NUMERIC(28, 10),
    contra_symbol VARCHAR(32),
    contra_name VARCHAR(255),
    provider_record_key VARCHAR(255) NOT NULL,
    source_provider VARCHAR(50) NOT NULL REFERENCES analyzer.data_provider (provider_key),
    raw_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_corporate_action_provider UNIQUE (source_provider, provider_record_key)
);

CREATE INDEX ix_corporate_action_instrument_date
    ON analyzer.corporate_action (instrument_id, action_date DESC);

CREATE TABLE analyzer.financial_report (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL REFERENCES analyzer.instrument (id),
    dimension VARCHAR(10) NOT NULL,
    calendar_date DATE,
    provider_date DATE NOT NULL,
    report_period DATE NOT NULL,
    fiscal_period VARCHAR(10),
    provider_last_updated DATE,
    revenue NUMERIC(28, 4),
    gross_profit NUMERIC(28, 4),
    operating_income NUMERIC(28, 4),
    net_income NUMERIC(28, 4),
    ebitda NUMERIC(28, 4),
    free_cash_flow NUMERIC(28, 4),
    operating_cash_flow NUMERIC(28, 4),
    capital_expenditure NUMERIC(28, 4),
    assets NUMERIC(28, 4),
    liabilities NUMERIC(28, 4),
    equity NUMERIC(28, 4),
    cash_and_equivalents NUMERIC(28, 4),
    debt NUMERIC(28, 4),
    shares_diluted NUMERIC(28, 4),
    eps_diluted NUMERIC(22, 8),
    gross_margin NUMERIC(18, 8),
    net_margin NUMERIC(18, 8),
    return_on_equity NUMERIC(18, 8),
    return_on_assets NUMERIC(18, 8),
    return_on_invested_capital NUMERIC(18, 8),
    current_ratio NUMERIC(18, 8),
    debt_to_equity NUMERIC(18, 8),
    price_earnings NUMERIC(18, 8),
    enterprise_value_ebitda NUMERIC(18, 8),
    price_sales NUMERIC(18, 8),
    market_cap NUMERIC(28, 4),
    source_provider VARCHAR(50) NOT NULL REFERENCES analyzer.data_provider (provider_key),
    raw_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_financial_report UNIQUE (
        instrument_id, dimension, provider_date, report_period, source_provider
    )
);

CREATE INDEX ix_financial_report_point_in_time
    ON analyzer.financial_report (instrument_id, provider_date DESC, dimension);

CREATE TABLE analyzer.news_item (
    id UUID PRIMARY KEY,
    source_provider VARCHAR(50) NOT NULL REFERENCES analyzer.data_provider (provider_key),
    provider_article_id VARCHAR(150) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    updated_at_provider TIMESTAMPTZ,
    title TEXT NOT NULL,
    teaser TEXT,
    body TEXT,
    article_url TEXT,
    author VARCHAR(255),
    channels JSONB NOT NULL DEFAULT '[]'::jsonb,
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    raw_data JSONB NOT NULL DEFAULT '{}'::jsonb,
    content_hash VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_news_provider_article UNIQUE (source_provider, provider_article_id)
);

CREATE INDEX ix_news_item_published_at ON analyzer.news_item (published_at DESC);

CREATE TABLE analyzer.news_item_instrument (
    news_item_id UUID NOT NULL REFERENCES analyzer.news_item (id) ON DELETE CASCADE,
    instrument_id UUID NOT NULL REFERENCES analyzer.instrument (id),
    provider_symbol VARCHAR(32),
    PRIMARY KEY (news_item_id, instrument_id)
);

CREATE TABLE analyzer.analysis_run (
    id UUID PRIMARY KEY,
    instrument_id UUID NOT NULL REFERENCES analyzer.instrument (id),
    requested_symbol VARCHAR(32) NOT NULL,
    status VARCHAR(30) NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    analysis_cutoff TIMESTAMPTZ,
    price_cutoff_date DATE,
    rule_set_version VARCHAR(50) NOT NULL,
    prompt_version VARCHAR(50),
    llm_provider VARCHAR(50),
    llm_model VARCHAR(100),
    data_quality_status VARCHAR(30),
    probability_up NUMERIC(8, 6),
    probability_status VARCHAR(50) NOT NULL DEFAULT 'MODEL_NOT_AVAILABLE',
    final_output JSONB,
    error_code VARCHAR(100),
    error_message TEXT,
    trace_id VARCHAR(64),
    duration_ms BIGINT
);

CREATE INDEX ix_analysis_run_instrument_date
    ON analyzer.analysis_run (instrument_id, requested_at DESC);

CREATE TABLE analyzer.analysis_component_result (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL REFERENCES analyzer.analysis_run (id) ON DELETE CASCADE,
    component_key VARCHAR(100) NOT NULL,
    component_version VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    summary TEXT,
    metrics JSONB NOT NULL DEFAULT '{}'::jsonb,
    reasons JSONB NOT NULL DEFAULT '[]'::jsonb,
    evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    limitations JSONB NOT NULL DEFAULT '[]'::jsonb,
    duration_ms BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_analysis_component UNIQUE (analysis_run_id, component_key)
);

CREATE TABLE analyzer.llm_invocation (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL REFERENCES analyzer.analysis_run (id) ON DELETE CASCADE,
    provider VARCHAR(50) NOT NULL,
    model VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    input_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    duration_ms BIGINT NOT NULL,
    trace_id VARCHAR(64),
    error_code VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE analyzer.batch_analysis (
    id UUID PRIMARY KEY,
    status VARCHAR(30) NOT NULL,
    total_items INTEGER NOT NULL,
    completed_items INTEGER NOT NULL DEFAULT 0,
    successful_items INTEGER NOT NULL DEFAULT 0,
    failed_items INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE TABLE analyzer.batch_analysis_item (
    id UUID PRIMARY KEY,
    batch_analysis_id UUID NOT NULL REFERENCES analyzer.batch_analysis (id) ON DELETE CASCADE,
    symbol VARCHAR(32) NOT NULL,
    status VARCHAR(30) NOT NULL,
    analysis_run_id UUID REFERENCES analyzer.analysis_run (id),
    error_code VARCHAR(100),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ux_batch_symbol UNIQUE (batch_analysis_id, symbol)
);
