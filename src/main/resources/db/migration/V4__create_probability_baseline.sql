CREATE TABLE analyzer.model_definition (
    id UUID PRIMARY KEY,
    model_version VARCHAR(100) NOT NULL UNIQUE,
    feature_set_version VARCHAR(50) NOT NULL,
    calibration_version VARCHAR(100) NOT NULL,
    algorithm VARCHAR(100) NOT NULL,
    target_definition TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    trained_at TIMESTAMPTZ NOT NULL,
    training_start_date DATE,
    training_end_date DATE,
    training_rows INTEGER NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX ux_model_definition_champion
    ON analyzer.model_definition (status)
    WHERE status = 'CHAMPION';

CREATE TABLE analyzer.model_artifact (
    id UUID PRIMARY KEY,
    model_definition_id UUID NOT NULL REFERENCES analyzer.model_definition (id),
    artifact_uri TEXT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    artifact_format VARCHAR(30) NOT NULL,
    schema_version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_model_artifact_hash UNIQUE (sha256)
);

CREATE TABLE analyzer.model_evaluation (
    id UUID PRIMARY KEY,
    model_definition_id UUID NOT NULL REFERENCES analyzer.model_definition (id) ON DELETE CASCADE,
    evaluation_type VARCHAR(50) NOT NULL,
    period_start DATE,
    period_end DATE,
    sample_count INTEGER NOT NULL,
    metrics JSONB NOT NULL,
    baselines JSONB NOT NULL DEFAULT '{}'::jsonb,
    calibration_bands JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE analyzer.feature_snapshot (
    id UUID PRIMARY KEY,
    analysis_run_id UUID REFERENCES analyzer.analysis_run (id) ON DELETE SET NULL,
    instrument_id UUID NOT NULL REFERENCES analyzer.instrument (id),
    cutoff_date DATE NOT NULL,
    target_session DATE NOT NULL,
    feature_set_version VARCHAR(50) NOT NULL,
    completeness_status VARCHAR(30) NOT NULL,
    features JSONB NOT NULL,
    missing_features JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_feature_snapshot UNIQUE (instrument_id, cutoff_date, feature_set_version)
);

CREATE INDEX ix_feature_snapshot_analysis_run ON analyzer.feature_snapshot (analysis_run_id);

CREATE TABLE analyzer.prediction (
    id UUID PRIMARY KEY,
    analysis_run_id UUID NOT NULL REFERENCES analyzer.analysis_run (id) ON DELETE CASCADE,
    instrument_id UUID NOT NULL REFERENCES analyzer.instrument (id),
    feature_snapshot_id UUID NOT NULL REFERENCES analyzer.feature_snapshot (id),
    model_definition_id UUID REFERENCES analyzer.model_definition (id),
    cutoff_date DATE NOT NULL,
    target_session DATE NOT NULL,
    probability_up NUMERIC(8, 6),
    status VARCHAR(50) NOT NULL,
    model_version VARCHAR(100),
    calibration_version VARCHAR(100),
    data_quality_status VARCHAR(30) NOT NULL,
    trace_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ux_prediction_analysis_run UNIQUE (analysis_run_id)
);

CREATE INDEX ix_prediction_instrument_date
    ON analyzer.prediction (instrument_id, cutoff_date DESC);

CREATE TABLE analyzer.prediction_outcome (
    id UUID PRIMARY KEY,
    prediction_id UUID NOT NULL UNIQUE REFERENCES analyzer.prediction (id) ON DELETE CASCADE,
    actual_session DATE NOT NULL,
    cutoff_adjusted_close NUMERIC(24, 8) NOT NULL,
    actual_adjusted_close NUMERIC(24, 8) NOT NULL,
    actual_up BOOLEAN NOT NULL,
    predicted_up BOOLEAN,
    correct BOOLEAN,
    brier_component NUMERIC(16, 12),
    resolved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
