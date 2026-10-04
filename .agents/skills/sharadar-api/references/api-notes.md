# Sharadar API notes

Source: [Sharadar `llms.txt`](https://sharadar.com/llms.txt), reviewed 2026-10-03.

The live source and linked table documentation override this fallback when they differ.

## Core API

- Base URL: `https://api.sharadar.com/v1.0`
- Data query: `GET /data/{table}`
- Public schemas: `GET /schema/{table}?format=postgres|sqlite|mysql`
- Authentication: `x-api-key` header is preferred for this project
- Slice filters: `ticker`, `from`, `to`, `fields`, `limit`, `offset`/`skip`, `sort`, and `format`
- Bulk table downloads: add `years=5`, `years=10`, or `years=full`; access depends on the subscription

Do not page a whole table using `limit` and `offset`. Use bulk downloads for whole-table ingestion and filtered queries for on-demand symbol synchronization.

## Official documentation

- [Introduction](https://sharadar.com/docs/intro)
- [Authentication](https://sharadar.com/docs/auth)
- [Querying](https://sharadar.com/docs/getting-started)
- [Bulk downloads](https://sharadar.com/docs/bulk)
- [FAQs](https://sharadar.com/docs/faqs)
- [Tickers](https://sharadar.com/docs/tickers)
- [Stock prices](https://sharadar.com/docs/stocks)
- [Fundamentals](https://sharadar.com/docs/fundamentals)
- [Daily fundamentals](https://sharadar.com/docs/daily)
- [Price metrics](https://sharadar.com/docs/metrics)
- [Corporate actions](https://sharadar.com/docs/actions)
- [Material events](https://sharadar.com/docs/events)

## Data interpretation reminders

- `permaticker` identifies a stable security/share class.
- Fundamentals distinguish `calendardate` from the provider's record date; inspect the table documentation before defining a point-in-time cutoff.
- Price data offers unadjusted, split-adjusted, and fully adjusted values.
- Coverage includes delisted securities.
- The documented `test-api-key` exposes only a small sample universe. HTTP 403 with an exceeded-free-tier message is an access limitation, not evidence that the endpoint is invalid.
