ALTER TABLE analyzer.batch_analysis_item
    ADD COLUMN reused_existing_analysis BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX ix_analysis_run_completed_daily_lookup
    ON analyzer.analysis_run (instrument_id, completed_at DESC)
    WHERE status = 'COMPLETED' AND final_output IS NOT NULL;
