CREATE TABLE analyzer.llm_information_gap (
    fingerprint VARCHAR(64) PRIMARY KEY,
    category VARCHAR(50) NOT NULL,
    requested_data TEXT NOT NULL,
    reason TEXT,
    importance VARCHAR(20) NOT NULL,
    preferred_source_type VARCHAR(50),
    workflow_status VARCHAR(30) NOT NULL DEFAULT 'NEW',
    occurrence_count BIGINT NOT NULL DEFAULT 1,
    first_requested_at TIMESTAMPTZ NOT NULL,
    last_requested_at TIMESTAMPTZ NOT NULL,
    resolution_notes TEXT,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT ck_llm_information_gap_status CHECK (
        workflow_status IN ('NEW', 'UNDER_REVIEW', 'PLANNED', 'AVAILABLE', 'REJECTED')
    ),
    CONSTRAINT ck_llm_information_gap_importance CHECK (
        importance IN ('LOW', 'MEDIUM', 'HIGH')
    )
);

CREATE INDEX ix_llm_information_gap_backlog
    ON analyzer.llm_information_gap (workflow_status, occurrence_count DESC, last_requested_at DESC);

CREATE TABLE analyzer.llm_information_request (
    id UUID PRIMARY KEY,
    llm_forecast_id UUID NOT NULL REFERENCES analyzer.llm_forecast (id) ON DELETE CASCADE,
    gap_fingerprint VARCHAR(64) NOT NULL REFERENCES analyzer.llm_information_gap (fingerprint),
    resolution_status VARCHAR(30) NOT NULL,
    search_query TEXT,
    web_provider VARCHAR(50),
    web_model VARCHAR(100),
    web_summary TEXT,
    web_sources JSONB NOT NULL DEFAULT '[]'::jsonb,
    input_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_code VARCHAR(100),
    error_message TEXT,
    raw_request JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_llm_information_request_forecast_gap UNIQUE (llm_forecast_id, gap_fingerprint),
    CONSTRAINT ck_llm_information_request_resolution CHECK (
        resolution_status IN (
            'WEB_RESOLVED', 'WEB_DISABLED', 'WEB_NOT_ALLOWED', 'WEB_FAILED',
            'NOT_SEARCHABLE', 'RESEARCH_LIMIT_REACHED'
        )
    )
);

CREATE INDEX ix_llm_information_request_forecast
    ON analyzer.llm_information_request (llm_forecast_id, created_at);

CREATE INDEX ix_llm_information_request_gap
    ON analyzer.llm_information_request (gap_fingerprint, created_at DESC);
