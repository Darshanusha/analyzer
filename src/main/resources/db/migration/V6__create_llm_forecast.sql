CREATE TABLE analyzer.llm_forecast (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL UNIQUE REFERENCES analyzer.analysis_run (id) ON DELETE CASCADE,
    instrument_id UUID NOT NULL REFERENCES analyzer.instrument (id),
    cutoff_date DATE NOT NULL,
    target_session DATE NOT NULL,
    status VARCHAR(30) NOT NULL,
    direction VARCHAR(20),
    up_score INTEGER,
    confidence VARCHAR(20),
    thesis TEXT,
    score_interpretation VARCHAR(150) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    model VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(50) NOT NULL,
    input_snapshot JSONB NOT NULL,
    input_sha256 VARCHAR(64) NOT NULL,
    response_payload JSONB,
    input_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    duration_ms BIGINT NOT NULL,
    error_code VARCHAR(100),
    error_message TEXT,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_llm_forecast_score CHECK (up_score IS NULL OR up_score BETWEEN 0 AND 100),
    CONSTRAINT ck_llm_forecast_direction CHECK (
        direction IS NULL OR direction IN ('UP', 'DOWN', 'UNCERTAIN')
    ),
    CONSTRAINT ck_llm_forecast_confidence CHECK (
        confidence IS NULL OR confidence IN ('LOW', 'MEDIUM', 'HIGH')
    )
);

CREATE INDEX ix_llm_forecast_instrument_date
    ON analyzer.llm_forecast (instrument_id, cutoff_date DESC);

CREATE INDEX ix_llm_forecast_available_score
    ON analyzer.llm_forecast (up_score DESC)
    WHERE status = 'AVAILABLE';

CREATE TABLE analyzer.llm_forecast_outcome (
    id UUID PRIMARY KEY,
    llm_forecast_id UUID NOT NULL UNIQUE REFERENCES analyzer.llm_forecast (id) ON DELETE CASCADE,
    actual_session DATE NOT NULL,
    cutoff_adjusted_close NUMERIC(24, 8) NOT NULL,
    actual_adjusted_close NUMERIC(24, 8) NOT NULL,
    actual_up BOOLEAN NOT NULL,
    predicted_up BOOLEAN,
    correct BOOLEAN,
    resolved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
