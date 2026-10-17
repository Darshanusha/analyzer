ALTER TABLE analyzer.llm_forecast
    DROP CONSTRAINT llm_forecast_analysis_run_id_key;

ALTER TABLE analyzer.llm_forecast
    ADD COLUMN horizon_trading_sessions INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN information_cutoff TIMESTAMPTZ;

ALTER TABLE analyzer.llm_forecast
    ADD CONSTRAINT uq_llm_forecast_run_horizon UNIQUE (analysis_run_id, horizon_trading_sessions),
    ADD CONSTRAINT ck_llm_forecast_horizon CHECK (horizon_trading_sessions IN (1, 5));

CREATE INDEX ix_llm_forecast_horizon_score
    ON analyzer.llm_forecast (horizon_trading_sessions, up_score DESC)
    WHERE status = 'AVAILABLE';

CREATE TABLE analyzer.ai_interaction (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL REFERENCES analyzer.analysis_run (id) ON DELETE CASCADE,
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
    web_search_count INTEGER NOT NULL DEFAULT 0,
    trace_id VARCHAR(64),
    error_code VARCHAR(100),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_ai_interaction_run ON analyzer.ai_interaction (analysis_run_id, round_number);

CREATE TABLE analyzer.ai_tool_call (
    id UUID PRIMARY KEY,
    ai_interaction_id UUID NOT NULL REFERENCES analyzer.ai_interaction (id) ON DELETE CASCADE,
    analysis_run_id UUID NOT NULL REFERENCES analyzer.analysis_run (id) ON DELETE CASCADE,
    call_id VARCHAR(100),
    tool_type VARCHAR(30) NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    status VARCHAR(30) NOT NULL,
    duration_ms BIGINT NOT NULL,
    result_count INTEGER,
    arguments_sha256 VARCHAR(64),
    error_code VARCHAR(100),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_ai_tool_call_run ON analyzer.ai_tool_call (analysis_run_id, tool_type, tool_name);
