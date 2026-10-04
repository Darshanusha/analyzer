UPDATE analyzer.instrument AS instrument
SET asset_type = 'FUND', updated_at = CURRENT_TIMESTAMP
FROM analyzer.provider_instrument_mapping AS mapping
WHERE mapping.instrument_id = instrument.id
  AND mapping.provider_key = 'SHARADAR'
  AND mapping.provider_entity_type = 'funds'
  AND mapping.valid_to IS NULL
  AND instrument.asset_type <> 'FUND';
