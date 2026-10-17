ALTER TABLE analyzer.llm_selection_interaction
    ADD COLUMN web_search_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE analyzer.llm_selection_tool_call
    ADD COLUMN tool_type VARCHAR(30) NOT NULL DEFAULT 'DATABASE';

CREATE TABLE analyzer.llm_selection_web_research (
    id UUID PRIMARY KEY,
    selection_run_id UUID NOT NULL REFERENCES analyzer.llm_selection_run (id) ON DELETE CASCADE,
    symbol VARCHAR(32) NOT NULL,
    information_cutoff TIMESTAMPTZ NOT NULL,
    status VARCHAR(30) NOT NULL,
    response_id VARCHAR(100),
    model VARCHAR(100) NOT NULL,
    summary TEXT,
    sources JSONB NOT NULL DEFAULT '[]'::jsonb,
    input_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    duration_ms BIGINT NOT NULL,
    web_search_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(100),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_llm_selection_web_symbol UNIQUE (selection_run_id, symbol)
);

CREATE INDEX ix_llm_selection_web_run
    ON analyzer.llm_selection_web_research (selection_run_id, symbol);
