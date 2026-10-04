CREATE TABLE analyzer.app_metadata (
    metadata_key VARCHAR(100) PRIMARY KEY,
    metadata_value JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO analyzer.app_metadata (metadata_key, metadata_value)
VALUES ('foundation', '{"phase": 0, "status": "initialized"}'::jsonb);
