CREATE TABLE analyzer.llm_selection_run (
    id UUID PRIMARY KEY,
    batch_analysis_id UUID NOT NULL REFERENCES analyzer.batch_analysis (id) ON DELETE CASCADE,
    status VARCHAR(30) NOT NULL,
    horizon_trading_sessions INTEGER NOT NULL,
    candidate_limit INTEGER NOT NULL,
    selection_limit INTEGER NOT NULL,
    candidate_count INTEGER NOT NULL DEFAULT 0,
    selected_count INTEGER NOT NULL DEFAULT 0,
    model VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(100) NOT NULL,
    input_snapshot JSONB,
    input_sha256 VARCHAR(64),
    response_payload JSONB,
    input_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    duration_ms BIGINT,
    error_code VARCHAR(100),
    error_message TEXT,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_llm_selection_horizon CHECK (horizon_trading_sessions IN (1, 5)),
    CONSTRAINT ck_llm_selection_limits CHECK (candidate_limit BETWEEN 3 AND 50
        AND selection_limit BETWEEN 1 AND candidate_limit)
);

CREATE INDEX ix_llm_selection_batch
    ON analyzer.llm_selection_run (batch_analysis_id, horizon_trading_sessions, created_at DESC);

CREATE TABLE analyzer.llm_selection_candidate (
    id UUID PRIMARY KEY,
    selection_run_id UUID NOT NULL REFERENCES analyzer.llm_selection_run (id) ON DELETE CASCADE,
    llm_forecast_id UUID NOT NULL REFERENCES analyzer.llm_forecast (id),
    symbol VARCHAR(32) NOT NULL,
    original_rank INTEGER NOT NULL,
    original_up_score INTEGER NOT NULL,
    original_confidence VARCHAR(20) NOT NULL,
    selected BOOLEAN NOT NULL,
    selected_rank INTEGER,
    selection_score INTEGER,
    confidence VARCHAR(20),
    comparative_thesis TEXT,
    bullish_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    bearish_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    comparative_advantages JSONB NOT NULL DEFAULT '[]'::jsonb,
    invalidation_risks JSONB NOT NULL DEFAULT '[]'::jsonb,
    data_limitations JSONB NOT NULL DEFAULT '[]'::jsonb,
    exclusion_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_llm_selection_candidate UNIQUE (selection_run_id, llm_forecast_id),
    CONSTRAINT uq_llm_selection_symbol UNIQUE (selection_run_id, symbol)
);

CREATE TABLE analyzer.llm_selection_interaction (
    id UUID PRIMARY KEY,
    selection_run_id UUID NOT NULL REFERENCES analyzer.llm_selection_run (id) ON DELETE CASCADE,
    provider VARCHAR(50) NOT NULL,
    model VARCHAR(100) NOT NULL,
    response_id VARCHAR(100),
    round_number INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    input_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    duration_ms BIGINT NOT NULL,
    tool_call_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(100),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE analyzer.llm_selection_tool_call (
    id UUID PRIMARY KEY,
    selection_interaction_id UUID NOT NULL REFERENCES analyzer.llm_selection_interaction (id) ON DELETE CASCADE,
    selection_run_id UUID NOT NULL REFERENCES analyzer.llm_selection_run (id) ON DELETE CASCADE,
    call_id VARCHAR(100),
    tool_name VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    duration_ms BIGINT NOT NULL,
    result_count INTEGER,
    arguments_sha256 VARCHAR(64),
    error_code VARCHAR(100),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_llm_selection_tool_run
    ON analyzer.llm_selection_tool_call (selection_run_id, tool_name);
