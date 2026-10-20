CREATE TABLE analyzer.agent_conversation (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    current_summary TEXT,
    message_count INTEGER NOT NULL DEFAULT 0,
    summarized_message_count INTEGER NOT NULL DEFAULT 0,
    summary_version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_conversation_counts CHECK (
        message_count >= 0 AND summarized_message_count >= 0
        AND summarized_message_count <= message_count
    )
);

CREATE INDEX ix_agent_conversation_updated
    ON analyzer.agent_conversation (updated_at DESC);

CREATE TABLE analyzer.agent_message (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES analyzer.agent_conversation (id) ON DELETE CASCADE,
    sequence_number INTEGER NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    openai_response_id VARCHAR(100),
    model VARCHAR(100),
    input_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    duration_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_message_sequence UNIQUE (conversation_id, sequence_number),
    CONSTRAINT ck_agent_message_role CHECK (role IN ('USER', 'ASSISTANT'))
);

CREATE INDEX ix_agent_message_conversation
    ON analyzer.agent_message (conversation_id, sequence_number);

CREATE TABLE analyzer.agent_tool_call (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES analyzer.agent_conversation (id) ON DELETE CASCADE,
    assistant_message_id UUID NOT NULL REFERENCES analyzer.agent_message (id) ON DELETE CASCADE,
    call_id VARCHAR(100),
    tool_name VARCHAR(100) NOT NULL,
    arguments JSONB NOT NULL DEFAULT '{}'::jsonb,
    result JSONB,
    status VARCHAR(30) NOT NULL,
    duration_ms BIGINT NOT NULL,
    result_count INTEGER,
    error_code VARCHAR(100),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_agent_tool_call_conversation
    ON analyzer.agent_tool_call (conversation_id, created_at);

CREATE TABLE analyzer.agent_conversation_summary (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES analyzer.agent_conversation (id) ON DELETE CASCADE,
    summary_version INTEGER NOT NULL,
    from_message_sequence INTEGER NOT NULL,
    through_message_sequence INTEGER NOT NULL,
    summary TEXT NOT NULL,
    model VARCHAR(100) NOT NULL,
    input_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    duration_ms BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_conversation_summary UNIQUE (conversation_id, summary_version),
    CONSTRAINT ck_agent_summary_range CHECK (
        from_message_sequence >= 1 AND through_message_sequence >= from_message_sequence
    )
);

CREATE INDEX ix_agent_summary_conversation
    ON analyzer.agent_conversation_summary (conversation_id, summary_version DESC);
