---
name: sharadar-api
description: Implement or review Sharadar integrations in this stock analyzer, including security identity, EOD prices, fundamentals, corporate actions, incremental synchronization, and point-in-time correctness.
---

# Sharadar API

Use Sharadar's current [`llms.txt`](https://sharadar.com/llms.txt) as the authoritative integration index. Open it before implementing or materially changing a Sharadar adapter, then open the linked documentation for the specific table being used. If it is unavailable, use [references/api-notes.md](references/api-notes.md) as a concise fallback.

## Integration invariants

- Use the direct API at `https://api.sharadar.com/v1.0`; do not use Nasdaq Data Link, Quandl, or their client libraries.
- Read the API key from external configuration and send it in the `x-api-key` header. Never hardcode it, ask the user to paste it into chat, or put it in logs, traces, exception messages, or URLs.
- Keep Sharadar behind application provider interfaces. Domain calculations and persistence models must not depend on Sharadar response types.
- Resolve securities through the `tickers` table and use `permaticker` as the stable provider identity. Do not treat ticker symbols as permanent identifiers.
- Fetch requested-symbol slices with `ticker`, `from`, `to`, and `fields`. For an entire table, use the provider's bulk download instead of paging through the complete dataset.
- Synchronize only missing or potentially revised ranges. Preserve canonical typed records and retain raw provider metadata only where licensing and the project schema permit it.
- Include delisted securities and corporate actions where required to prevent survivorship bias and broken historical identity.
- Maintain point-in-time correctness: do not make a historical observation visible before its provider date or filing availability date.
- Distinguish unadjusted, split-adjusted, and fully adjusted prices. Select the series explicitly for each calculation and record that choice.
- Treat provider responses as untrusted input: validate required identifiers, dates, numeric ranges, duplicates, ordering, and empty responses before persistence.

## Table routing

- `tickers`: security master and metadata
- `stocks` / `SEP`: daily U.S. equity OHLCV and adjusted prices
- `fundamentals` / `SF1`: financial statements and fundamental facts
- `daily`: daily fundamental snapshots
- `metrics`: price-derived metrics
- `actions`: splits, dividends, ticker changes, and other corporate actions
- `events`: material corporate events
- `sp500`: historical index membership

Prefer modern descriptive table names in new code. Legacy aliases are acceptable when compatibility or provider documentation makes them clearer.

## Verification

- Unit-test response mapping and error translation with a mock HTTP server.
- Integration-test migrations, upserts, idempotency, and incremental range selection against PostgreSQL.
- Keep ordinary automated tests independent of paid credentials and live-provider availability.
- Use `test-api-key` only for an intentional live sample check; it covers a limited sample universe and must not be treated as production access.

