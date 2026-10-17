ALTER TABLE analyzer.batch_analysis
    ADD COLUMN filter_enabled BOOLEAN NOT NULL DEFAULT TRUE;
