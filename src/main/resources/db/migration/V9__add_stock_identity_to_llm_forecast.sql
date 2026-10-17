ALTER TABLE analyzer.llm_forecast
    ADD COLUMN symbol VARCHAR(32),
    ADD COLUMN stock_name VARCHAR(255);

UPDATE analyzer.llm_forecast forecast
SET symbol = instrument.current_symbol,
    stock_name = instrument.name
FROM analyzer.instrument instrument
WHERE instrument.id = forecast.instrument_id;

ALTER TABLE analyzer.llm_forecast
    ALTER COLUMN symbol SET NOT NULL,
    ALTER COLUMN stock_name SET NOT NULL;

CREATE INDEX ix_llm_forecast_symbol_created
    ON analyzer.llm_forecast (symbol, created_at DESC);
