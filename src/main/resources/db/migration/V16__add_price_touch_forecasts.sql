ALTER TABLE analyzer.llm_forecast
    ADD COLUMN upside_threshold_percent NUMERIC(8,4),
    ADD COLUMN downside_threshold_percent NUMERIC(8,4),
    ADD COLUMN upside_touch_score INTEGER,
    ADD COLUMN downside_touch_score INTEGER,
    ADD COLUMN touch_score_spread INTEGER,
    ADD CONSTRAINT ck_llm_forecast_touch_thresholds CHECK (
        (upside_threshold_percent IS NULL OR upside_threshold_percent > 0)
        AND (downside_threshold_percent IS NULL OR downside_threshold_percent > 0)
    ),
    ADD CONSTRAINT ck_llm_forecast_touch_scores CHECK (
        (upside_touch_score IS NULL OR upside_touch_score BETWEEN 0 AND 100)
        AND (downside_touch_score IS NULL OR downside_touch_score BETWEEN 0 AND 100)
    );

CREATE INDEX ix_llm_forecast_touch_ranking
    ON analyzer.llm_forecast (horizon_trading_sessions, touch_score_spread DESC, upside_touch_score DESC)
    WHERE status = 'AVAILABLE' AND upside_touch_score IS NOT NULL AND downside_touch_score IS NOT NULL;

ALTER TABLE analyzer.llm_forecast_outcome
    ADD COLUMN upside_target_touched BOOLEAN,
    ADD COLUMN downside_target_touched BOOLEAN,
    ADD COLUMN upside_first_touch_date DATE,
    ADD COLUMN downside_first_touch_date DATE,
    ADD COLUMN touch_sequence VARCHAR(30),
    ADD COLUMN maximum_adjusted_high NUMERIC(20,6),
    ADD COLUMN minimum_adjusted_low NUMERIC(20,6),
    ADD COLUMN maximum_return_percent NUMERIC(12,6),
    ADD COLUMN maximum_drawdown_percent NUMERIC(12,6),
    ADD COLUMN horizon_return_percent NUMERIC(12,6),
    ADD CONSTRAINT ck_llm_forecast_touch_sequence CHECK (
        touch_sequence IS NULL OR touch_sequence IN (
            'TARGET_FIRST', 'DOWNSIDE_FIRST', 'SAME_SESSION_AMBIGUOUS',
            'TARGET_ONLY', 'DOWNSIDE_ONLY', 'NEITHER'
        )
    );

ALTER TABLE analyzer.llm_selection_run
    ADD COLUMN objective VARCHAR(50) NOT NULL DEFAULT 'CLOSE_DIRECTION',
    ADD COLUMN upside_threshold_percent NUMERIC(8,4),
    ADD COLUMN downside_threshold_percent NUMERIC(8,4);

ALTER TABLE analyzer.llm_selection_candidate
    ADD COLUMN original_upside_touch_score INTEGER,
    ADD COLUMN original_downside_touch_score INTEGER,
    ADD COLUMN original_touch_score_spread INTEGER;
