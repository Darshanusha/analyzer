# AI Stock Market Analyzer

An explainable, AI-assisted U.S. stock analysis application built with Java, Spring Boot, Spring AI, PostgreSQL, and Docker.

The application combines deterministic financial calculations, technical analysis, peer/sector context, market conditions,
and news events. OpenAI can produce an optional structured next-session evidence forecast and comparative up-score. A
separately trained and calibrated statistical model remains available as an independent path for a true probability.

> This project is for personal research and education. It is not financial advice and is not designed to execute trades.

## Working agreement

This is a design-first project. For every significant feature or architectural change:

1. Understand and discuss the requirement.
2. Explain the proposed design, alternatives, risks, and assumptions.
3. Obtain explicit approval.
4. Implement one small, testable change at a time.

Do not introduce dependencies, infrastructure, or AI components solely because they are available. Each component must solve a concrete problem.

## Goals

The project has two equally important goals:

- Build a serious, explainable stock-analysis application.
- Learn production-quality Spring Boot, Spring AI, Docker, observability, data engineering, backtesting, and model evaluation.

## Agreed scope

| Area | Decision |
|---|---|
| Intended user | Personal/single user |
| Market | U.S. equities |
| Market timeframe | Daily end-of-day (EOD) |
| Data loading | On demand when a stock is requested |
| Price and fundamental provider | Sharadar |
| News provider | Benzinga |
| LLM provider | OpenAI through Spring AI |
| Database | Local PostgreSQL initially |
| Deployment | Docker Compose locally, cloud-ready later |
| Prediction target | Probability of a higher adjusted close on the next trading session |
| Batch input | CSV containing stock symbols |
| User-facing conclusion | Evidence, an experimental UP/DOWN/UNCERTAIN forecast, and no BUY/SELL recommendation |

Provider access and licensing must be verified for the selected Sharadar and Benzinga subscriptions before their integrations are implemented.

## Core design principles

### Deterministic code owns facts and calculations

Java code will calculate financial ratios, technical indicators, sector aggregates, comparisons, data-quality checks, and rule results. An LLM will not be asked to perform calculations that normal code can perform reliably.

### The LLM synthesizes evidence

OpenAI receives a compact package of verified facts and calculated results. It can produce:

- Important fundamental observations
- Peer, industry, and sector context
- Technical and market conditions
- Material news and events
- Supporting and opposing evidence
- Conflicting evidence
- Missing information and data limitations
- What the user may want to monitor
- An experimental next-session UP/DOWN/UNCERTAIN direction
- An uncalibrated comparative up-score for ranking a batch

The LLM must reference supplied evidence and must not invent market data, claim that its score is a probability, or
recommend executing a trade.

### Probability is statistically produced

A score such as `63/100` is not automatically a `63%` probability. `probabilityUp` will remain unavailable until an out-of-sample-tested and calibrated statistical model exists.

### Point-in-time correctness

Historical analysis and backtesting may use only information that was available at the analysis cutoff. This includes filing publication dates, news timestamps, completed EOD sessions, symbol changes, corporate actions, and delisted securities.

### Providers are replaceable

Application logic will consume canonical internal models through provider interfaces. Sharadar, Benzinga, and OpenAI are initial adapters rather than dependencies embedded throughout the domain.

## High-level architecture

```text
                         External providers
                +--------------+--------------+
                | Sharadar     | Benzinga     | OpenAI
                +------+-------+------+-------+----+----+
                       |              |            |
                       v              v            v
+---------------------------------------------------------------+
|                    Spring Boot Analyzer API                   |
|                                                               |
|  API -> Analysis Orchestrator                                 |
|           |                                                   |
|           +-> Instrument resolution                           |
|           +-> On-demand data synchronization                  |
|           +-> Fundamental analysis                            |
|           +-> Peer, industry, and sector analysis             |
|           +-> Technical analysis                              |
|           +-> Market-regime and relative-strength analysis    |
|           +-> News, material-event, and catalyst analysis     |
|           +-> Data-quality assessment                         |
|           +-> Prediction inference (when model is available)  |
|           +-> OpenAI structured evidence forecast             |
|           +-> Deterministic evidence narrative                |
+-----------------------------+---------------------------------+
                              |
                              v
                         PostgreSQL
```

The application will begin as a modular monolith. This provides clear boundaries and straightforward local operation without premature microservices. Offline Python model training will later run as a separate container because it has a different runtime and dependency lifecycle.

## Docker topology

```text
Docker Compose
|-- analyzer-api
|-- postgres
|-- prometheus
|-- tempo
|-- loki
|-- grafana-alloy
|-- grafana
`-- model-trainer       # Added during the prediction-model phase
```

Local persistent volumes will hold PostgreSQL data, Prometheus metrics, Tempo traces, Loki logs, Grafana configuration, and model artifacts. The application container will remain stateless. Configuration and secrets will be supplied externally through environment variables or secret management rather than embedded in an image.

## Application modules

```text
analyzer
|-- api
|   |-- analysis
|   |-- batch
|   `-- error
|-- application
|   |-- orchestration
|   |-- synchronization
|   `-- outcome
|-- instrument
|-- provider
|   |-- sharadar
|   |-- benzinga
|   `-- openai
|-- marketdata
|-- fundamental
|   |-- company
|   |-- peer
|   |-- industry
|   `-- sector
|-- technical
|-- marketregime
|-- news
|-- catalyst
|-- quality
|-- prediction
|-- narrative
|-- persistence
|-- observability
`-- configuration
```

These are modules inside one Spring Boot deployment, not independent services.

## Provider interfaces

The domain will depend on contracts such as:

```java
interface MarketDataProvider {}
interface FundamentalDataProvider {}
interface CorporateActionProvider {}
interface NewsProvider {}
interface LlmProvider {}
```

Initial adapters will be:

- Sharadar market-data, fundamental-data, and corporate-action adapters
- Benzinga news adapter
- OpenAI adapter through Spring AI

Replacing a provider should not require changing financial calculations or analysis orchestration.

## On-demand data synchronization

There will be no mandatory daily ingestion job in the initial version.

When a symbol is analyzed, the application will:

1. Resolve the submitted symbol to a permanent internal instrument.
2. Determine the most recent completed U.S. market session.
3. Inspect locally available data and provider synchronization state.
4. Fetch only missing or potentially revised data.
5. Validate and normalize the response.
6. Store canonical records and permitted raw provider records.
7. Run the requested analysis.

The first request for a symbol may download years of history. Later requests should normally fetch only incremental data.

Ticker symbols are not permanent identities. The application will maintain an internal `instrument_id`, provider identifiers, CIK/FIGI where available, and symbol history.

## Analysis pipeline

```text
Raw provider data
        |
        v
Validation and canonical normalization
        |
        v
Calculated metrics and feature snapshots
        |
        +-> Fundamental analysis
        +-> Peer/industry/sector analysis
        +-> Technical analysis
        +-> Market-regime analysis
        +-> News/event analysis
        +-> Data-quality assessment
        |
        v
Prediction model inference (when available)
        |
        v
Curated evidence package
        |
        v
OpenAI structured narrative
        |
        v
Persisted analysis and API response
```

## Extensible analysis framework

Each analysis component will follow a common conceptual contract:

```java
interface AnalysisModule {
    String key();
    String version();
    Set<DataRequirement> requirements();
    AnalysisComponentResult analyze(AnalysisContext context);
}
```

Initial module keys may include:

```text
fundamental.company
fundamental.peer
fundamental.industry
fundamental.sector
technical.trend
technical.momentum
technical.volatility
technical.volume
technical.price-structure
market.regime
market.relative-strength
news.events
news.materiality
catalyst.calendar
quality.data
```

New calculations remain tested code. Configurable thresholds, weights, parameters, and enablement are versioned. Once used for an analysis, a rule-set version is immutable; changes create a new version.

## Fundamental analysis

### Company analysis

- Revenue, EPS, and free-cash-flow growth
- Growth consistency and acceleration/deceleration
- Gross, operating, and net margins
- ROE, ROA, and ROIC
- Operating and free cash flow
- Cash-flow quality and accrual indicators
- Liquidity, leverage, and interest coverage
- Share dilution, buybacks, and dividends
- P/E, price-to-sales, EV/EBITDA, and free-cash-flow yield
- Historical valuation and balance-sheet strength

### Relative context

- Peer-group comparisons
- Industry aggregate and median growth
- Sector aggregate and median growth
- Profitability and valuation percentiles
- Company performance relative to its peer group

Peer selection will consider industry, business model, size, profitability stage, geography, liquidity, and available history. The peer list and selection version must be recorded with the result.

## Technical and market analysis

The initial technical feature set will be deliberately small and diverse:

- Trend: SMA/EMA alignment, slopes, and price distance
- Momentum: RSI, MACD, and rate of change
- Volatility: ATR, realized volatility, and Bollinger Band width
- Volume: relative volume, volume trend, and price/volume divergence
- Price structure: gaps, drawdowns, 52-week position, support, and resistance
- Relative strength: stock versus market, sector, and peers
- Market regime: index trend, volatility, breadth, and risk-on/risk-off conditions

Additional indicators will be retained only when out-of-sample testing shows that they add useful information.

## News, events, and catalysts

Benzinga articles will be normalized and deduplicated into events rather than treated as independent sentiment votes.

Potential event types include:

- Earnings and guidance
- Management changes
- Mergers and acquisitions
- Regulatory actions and litigation
- Product launches, failures, or recalls
- Cybersecurity incidents
- Customer or supplier changes
- Financing, share issuance, buybacks, and dividends
- Analyst actions

Each event may include materiality, recency, affected entity, confirmation status, expected time horizon, source references, and uncertainty. Known catalysts such as earnings, ex-dividend dates, investor events, and important macroeconomic releases should also be surfaced where reliable data is available.

## OpenAI and Spring AI

Spring AI will be used where AI provides real value:

- Structured news-event extraction
- Summarizing unstructured evidence
- Explaining relationships among verified results
- Identifying conflicts, risks, and missing information
- Producing a readable, evidence-linked narrative
- Producing a schema-constrained experimental next-session forecast
- Ranking forecasts produced by the same prompt and model version

The model will receive a curated context rather than all raw historical data. Stable instructions and prompt templates
are versioned under `src/main/resources/prompts/{prompt-version}.txt`; the configured prompt version must have a matching
resource or application startup fails closed. Every invocation records the provider, model, prompt version, token usage,
duration, outcome, and trace ID.

The forecast may also return structured `informationRequests[]` when a specific missing fact could materially change
the assessment. The application records every request in an information-gap backlog. When explicitly enabled, one
bounded research round may use OpenAI Responses API web search against an allowlist of authoritative domains. Search
results, source URLs, model/token usage, latency, and failures are stored before a final forecast call receives the
additional evidence. The LLM never receives database access, provider credentials, arbitrary HTTP access, or permission
to execute writes.

The expected structured narrative contains:

```text
overallObservation
fundamentalObservations[]
peerAndSectorObservations[]
technicalObservations[]
marketObservations[]
newsObservations[]
supportingEvidence[]
opposingEvidence[]
conflictingEvidence[]
missingInformation[]
dataLimitations[]
whatToMonitor[]
```

Raw HTTP prompt and completion bodies are not written to application logs. PostgreSQL stores the normalized evidence
snapshot, its SHA-256 hash, and the parsed schema-validated output so forecasts are reproducible and auditable.

The implemented forecast contains:

```text
upScore                         # 0-100 comparative score; not a probability
direction                       # derived by Java: UP, DOWN, or UNCERTAIN
confidence                      # LOW, MEDIUM, or HIGH evidence confidence
thesis
bullishEvidence[]
bearishEvidence[]
risks[]
dataLimitations[]
informationRequests[]           # structured missing-data requests; empty when evidence is sufficient
```

## Next-trading-day probability

The initial target is precisely defined as:

```text
P(next U.S. trading session adjusted close
  > current completed session adjusted close
  | information available at the prediction cutoff)
```

The prediction must record:

- Analysis and information cutoff timestamps
- Target trading session
- Up probability
- Model and calibration versions
- Data-quality state
- Feature-snapshot version

Until a trained and calibrated model exists, the API will return:

```json
{
  "probabilityUp": null,
  "status": "MODEL_NOT_AVAILABLE"
}
```

The first model should be a transparent logistic-regression baseline trained offline in Python. Candidate features include price returns, trend, momentum, volatility, volume, relative strength, market/sector state, catalyst proximity, material events, and selected point-in-time fundamental changes.

Fundamentals will remain important for company analysis, but their value for a one-day prediction must be proven through testing rather than assumed.

## Backtesting and model evaluation

Backtesting is a core subsystem. It will use time-aware walk-forward validation and prevent look-ahead and survivorship bias.

Evaluation will include:

- Accuracy and balanced accuracy
- Precision, recall, and F1 where useful
- Brier score and log loss
- Calibration curves and calibration error
- Performance by probability band
- Performance by market regime
- Benchmark-relative returns
- Transaction costs and slippage
- Drawdown and risk-adjusted results
- Prediction coverage and abstention rate

The model will be compared with simple baselines such as unconditional up frequency, always-up, previous-day direction, market-only, and simple momentum models.

A failed prediction will be stored as an outcome. It will not immediately modify the live model. Learning occurs through controlled batch retraining, evaluation, versioning, and explicit model promotion.

## API design

### Analyze one stock

```http
POST /api/v1/analyses
Content-Type: application/json
Idempotency-Key: unique-request-id

{
  "symbol": "AAPL",
  "waitForCompletion": true,
  "maximumWaitSeconds": 60,
  "forceReanalysis": false,
  "filter": true
}
```

If processing completes within the waiting period, the API returns `200 OK` with the complete analysis. If it takes longer, it returns `202 Accepted` with an `analysisRunId` and continues in the background.
By default, a completed successful analysis for the same stock on the current UTC date is reused. Send
`forceReanalysis: true` to create a new run. Reused single-stock responses include `X-Analysis-Reused: true`.
The `filter` flag defaults to `true`. Java evaluates the deterministic evidence before any OpenAI forecast call;
set `filter: false` to bypass that candidate gate.

```http
GET /api/v1/analyses/{analysisRunId}
```

Other planned endpoints:

```http
GET /api/v1/instruments/{symbol}/analyses
GET /api/v1/instruments/{symbol}/predictions
```

### Analyze a CSV batch

```http
POST /api/v1/batches?forceReanalysis=false&filter=true
Content-Type: multipart/form-data
```

Initial CSV format:

```csv
symbol
AAPL
MSFT
NVDA
```

CSV processing is asynchronous, deduplicates symbols, processes stocks independently, and permits partial success.
With the default `filter=true`, every stock still receives deterministic analysis, but only Java-qualified candidates
are sent to the LLM. Use `filter=false` when every successfully analyzed stock must be sent to the LLM.

```http
GET /api/v1/batches/{batchAnalysisId}
GET /api/v1/batches/{batchAnalysisId}/items
```

## Persistence design

PostgreSQL will use a hybrid relational and JSONB design. Frequently queried, joined, constrained, or calculated fields will use typed columns. Provider-specific metadata, additional details, versioned configuration, and structured LLM output may use JSONB.

### Reference and provider data

```text
data_provider
instrument
provider_instrument_mapping
symbol_history
instrument_classification
market_session
provider_sync_state
ingestion_run
raw_provider_record
provider_request_audit
```

### Canonical financial data

```text
daily_price_bar
corporate_action
financial_report
financial_fact
news_item
news_item_instrument
material_event
catalyst_event
```

### Derived data

```text
technical_snapshot
fundamental_snapshot
peer_comparison_snapshot
industry_snapshot
sector_snapshot
market_regime_snapshot
feature_snapshot
```

### Configuration and results

```text
analysis_module_definition
metric_definition
rule_set
model_definition
prompt_definition
analysis_run
analysis_component_result
analysis_metric_result
decision_reason
evidence_reference
analysis_explanation
llm_invocation
```

### Predictions and batches

```text
prediction
prediction_outcome
model_evaluation
model_artifact
batch_analysis
batch_analysis_item
```

Operational metrics will not be stored primarily in PostgreSQL. Important per-analysis audit facts such as provider durations, token usage, estimated cost, model version, and trace ID will be stored there permanently.

## Observability

```text
Spring Boot metrics -> Prometheus -> Grafana
OpenTelemetry traces -> Tempo -> Grafana
Structured logs -> Grafana Alloy -> Loki -> Grafana
```

Each analysis will carry an `analysisRunId`, `requestId`, `traceId`, and optional `batchAnalysisId`.

Example trace:

```text
stock.analysis
|-- instrument.resolve
|-- sharadar.synchronize
|-- benzinga.synchronize
|-- fundamental.analysis
|-- technical.analysis
|-- market-regime.analysis
|-- prediction.inference
|-- openai.request
`-- database.persistence
```

Metrics will cover application health, HTTP latency, JVM resources, database pools, external-provider latency/errors, data freshness, analysis duration, LLM tokens/cost, model failures, and prediction evaluation. High-cardinality values such as ticker and analysis IDs belong in traces and logs, not Prometheus labels.

## Security

- Keep provider keys and passwords outside source control.
- Use environment variables locally and managed secrets in the cloud.
- Protect the personal API with an API key initially.
- Restrict Actuator endpoints to internal access.
- Validate symbols, dates, CSV files, provider responses, and LLM outputs.
- Never log authorization headers or secrets.
- Treat external news and filings as untrusted content.
- Do not give the LLM unrestricted tool or command access.
- Apply timeouts, bounded retries, and rate-limit handling to provider calls.

## Phased development strategy

### Phase 0 — Foundation

Deliver:

- Docker Compose foundation
- PostgreSQL and Flyway
- Configuration and secret conventions
- Provider interfaces and canonical error model
- Spring Boot Actuator
- Prometheus, Tempo, Loki, Grafana Alloy, and Grafana
- Structured logging, correlation IDs, and tracing
- Basic application and integration-test structure

Exit condition: the application and infrastructure start reliably, migrations run, PostgreSQL persists data, and telemetry is visible in Grafana.

### Phase 1 — Explainable stock analysis

Deliver:

- Instrument resolution and symbol history
- On-demand Sharadar synchronization
- Canonical price, corporate-action, and fundamental storage
- Fundamental analysis
- Peer, industry, and sector analysis
- Technical indicators
- Market-regime and relative-strength analysis
- Benzinga news ingestion, deduplication, and event extraction
- Data-quality assessment
- OpenAI structured, evidence-linked narrative
- Single-stock API with synchronous wait and asynchronous fallback
- Asynchronous CSV batch API
- Analysis history and audit records

Exit condition: requesting a symbol produces a stored, reproducible, evidence-linked analysis based only on data available at its cutoff. `probabilityUp` remains `null` during this phase.

### Phase 2 — Probability baseline

Deliver:

- Point-in-time feature snapshots
- Next-session outcome labels
- Python training container
- Logistic-regression baseline
- Walk-forward validation
- Probability calibration
- Versioned model artifacts
- Java inference integration
- Prediction history and outcome APIs
- Model-performance dashboards

Exit condition: every published percentage comes from a frozen, versioned, out-of-sample-tested, and calibrated model.

Phase 2 implementation uses the versioned `phase2-v1` feature contract:

- 1-, 5-, and 20-session adjusted-close returns
- Price distance from SMA20 and SMA50
- RSI14 normalized to the range 0–1
- 20-session annualized volatility
- Relative volume against the preceding 20 sessions
- SPY 20-session return
- 20-session relative strength versus SPY

The Python trainer builds next-observed-session labels and uses five expanding walk-forward folds. Scaling and
logistic-regression fitting happen inside each fold using only earlier dates. Platt calibration for a fold may use
only out-of-sample logits from earlier folds. The final deployable scaler, classifier, and calibrator are then fitted
and written together as a portable JSON candidate artifact.

Candidate creation never changes live inference. Promotion is a separate explicit command, and promotion normally
refuses a candidate whose out-of-sample Brier score does not beat the unconditional-frequency baseline.

### Phase 3 — Model lifecycle and learning

Deliver:

- Outcome resolution when new EOD data is fetched
- Performance evaluation by market regime and probability band
- Drift and data-quality monitoring
- Champion/challenger comparison
- Controlled manual or scheduled retraining
- Model promotion and rollback
- Feature-importance and failure-analysis reports

Exit condition: model changes are reproducible, evaluated against the current production model, and promoted explicitly rather than learning directly from a single failure.

### Phase 4 — Cloud readiness

Deliver:

- Container registry and automated image builds
- Managed PostgreSQL
- Managed secrets
- Object storage for model artifacts and large exports
- HTTPS and cloud networking
- Automated backups and restore testing
- Hosted observability or cloud-compatible telemetry export
- Resource, retention, and cost controls

Exit condition: the same application image runs in the selected cloud environment without changing domain logic.

### Phase 5 — Optional expansion

Possible later additions:

- UI/dashboard
- SEC filing and earnings-call analysis
- Analyst-estimate revisions
- Insider and institutional activity
- Options activity
- Macroeconomic features
- RAG over filings and long-form documents when justified
- Additional providers and prediction horizons

Each addition requires a separate design discussion and approval.

## Recommended implementation order

1. Docker and PostgreSQL
2. Flyway schema and canonical identifiers
3. Observability foundation
4. Provider interfaces
5. Sharadar ingestion for one stock
6. Fundamental, peer, industry, and sector calculations
7. Technical and market-regime calculations
8. Benzinga ingestion and event normalization
9. OpenAI structured narrative
10. Single-stock API
11. CSV batch processing
12. Prediction outcome tracking
13. Python probability model
14. Java model inference
15. Model monitoring and retraining lifecycle

## Current project status

Phase 0 foundation is implemented and currently includes:

- Spring Boot web, validation, JPA, Flyway, Actuator, Prometheus, and OpenTelemetry dependencies
- PostgreSQL configuration and a minimal foundation migration
- Dockerfile and Docker Compose infrastructure
- PostgreSQL, Prometheus, Tempo, Loki, Alloy, and Grafana services
- Persistent Docker volumes and provisioned Grafana data sources
- A starter Grafana application-overview dashboard
- Structured ECS console logging
- Request correlation IDs and a canonical API error envelope
- Unit tests and an opt-in PostgreSQL/Testcontainers integration test profile

Phase 0 is complete. The Phase 1 deterministic analysis pipeline is also implemented:

- Canonical instrument identity, Sharadar permanent identifiers, and symbol history
- Replaceable provider interfaces for reference data, prices, fundamentals, corporate actions, and news
- On-demand, overlap-window synchronization that fetches missing data only when a symbol is analyzed
- Sharadar EOD prices, point-in-time fundamentals, and corporate actions stored in PostgreSQL
- Benzinga news ingestion, deduplication, and stock association
- Fundamental growth, profitability, balance-sheet, cash-flow, and valuation observations
- Local peer/industry/sector comparison that expands as more stocks are analyzed
- SMA20/50/200, RSI14, 1/2/5/10/20-session returns, 5/20-session volatility, fully adjusted ATR,
  ATR as a percentage of price, relative volume, recent drawdown, 52-week position, and support/resistance distance
- SPY market-regime and sector-ETF relative-strength analysis
- News event/keyword screening and an explicit data-quality component
- A durable `analysis_run` plus one durable, versioned log row per analysis component
- Same-call completion with a configurable wait and `202 Accepted` polling fallback
- Asynchronous, deduplicated CSV batch analysis with partial-success handling
- Analysis history and detailed batch status endpoints
- A deterministic evidence narrative when external LLM narration is disabled
- Optional OpenAI next-session forecasts using schema-constrained structured output
- Durable LLM input snapshots, prompt/model versions, token usage, latency, outcomes, and batch rankings
- Bounded allowlisted web research plus a durable missing-information backlog

Phase 1 deliberately returns `probabilityUp: null` and `probabilityStatus: MODEL_NOT_AVAILABLE`.
A percentage will only be introduced with the trained, calibrated Phase 2 model.

OpenAI transmission is opt-in through `LLM_FORECAST_ENABLED=true`. The forecast sends normalized component metrics,
reasons, limitations, and at most the news headlines already present in component evidence; it does not send raw price
history, full fundamental source records, news bodies, or provider credentials. The narrative remains
`DETERMINISTIC_FALLBACK`; the LLM is used only for the separate experimental next-session forecast.

An LLM `upScore`, `upsideTouchScore`, or `downsideTouchScore` is deliberately not placed in `probabilityUp`. These are
uncalibrated comparative estimates from 0 to 100, not statistical probabilities or trade recommendations.
`probabilityUp` remains reserved for a separately evaluated, calibrated model. The application records every LLM
forecast and resolves its close-direction and barrier-touch outcomes after the required EOD bars arrive.

Web research is separately opt-in and disabled by default. The local `get_recent_news` database tool is also disabled
by default because an empty news store gives the model no useful evidence. While that tool is disabled, the model may
make up to five hosted web-search calls per forecast. If local news is enabled later, hosted search is limited to one
call so the model prefers the point-in-time data already stored by the application. The initial web allowlist contains
SEC and U.S. macroeconomic authority domains. Unsupported requests (for example options or short-interest data without
an approved provider) are not silently discarded: they are stored in PostgreSQL with an occurrence count so frequently
requested integrations can be prioritized.

Phase 2 probability-baseline infrastructure is implemented:

- Flyway V4 tables for feature snapshots, models, artifacts, evaluation, predictions, and outcomes
- A point-in-time Java feature builder shared with the Python feature contract
- Automatic next-session outcome resolution when later EOD data is fetched
- A containerized Python logistic-regression trainer
- Expanding walk-forward evaluation, baseline comparison, and Platt calibration
- Versioned candidate artifacts and explicit champion promotion
- Hot-reloaded Java inference from `model-artifacts/champion.json`
- Prediction history at `GET /api/v1/instruments/{symbol}/predictions`
- PostgreSQL-backed accuracy/Brier gauges and a provisioned Grafana model-performance dashboard

No model artifact is committed, and the application never invents a default percentage. Until you train, review,
and promote a candidate using your locally stored historical universe, analyses continue to return
`probabilityUp: null` with `probabilityStatus: MODEL_NOT_AVAILABLE`.

## Running the application

### Prerequisites

- Java 25
- Docker Desktop with Linux containers
- Docker Compose

Copy `.env.example` to `.env` if you want to override the local defaults. Never commit `.env`.

### IntelliJ development mode

Start the infrastructure:

```powershell
docker compose up -d
```

Then run `AnalyzerApplication` from IntelliJ. The default application configuration connects to PostgreSQL and Tempo through their published localhost ports. Prometheus scrapes the application through `host.docker.internal:8080`.

For live analysis, set at least `SHARADAR_API_KEY`. Set `BENZINGA_API_KEY` to include recent news; without it,
the analysis still completes and records news as unavailable. Keep `-Duser.timezone=UTC` in the IntelliJ VM options.

To enable the experimental OpenAI forecast, add these IntelliJ environment variables and restart the application:

```text
OPENAI_CHAT_PROVIDER=openai
OPENAI_API_KEY=<your key>
OPENAI_MODEL=gpt-6.1-sol
STOCK_TARGET_UPSIDE_PERCENT=2.0
STOCK_TARGET_DOWNSIDE_PERCENT=2.0
BATCH_ANALYSIS_MAX_CONCURRENCY=4
BATCH_ANALYSIS_QUEUE_CAPACITY=600
OPENAI_MAX_CONCURRENT_REQUESTS=4
LLM_SELECTION_WEB_RESEARCH_MAX_CONCURRENCY=4
SHARADAR_MAX_CONCURRENT_REQUESTS=2
EXTERNAL_REQUEST_PERMIT_TIMEOUT=5m
EXTERNAL_REQUEST_MAX_RETRIES=2
EXTERNAL_REQUEST_INITIAL_RETRY_DELAY=1s
EXTERNAL_REQUEST_MAX_RETRY_DELAY=30s
LLM_FORECAST_ENABLED=true
LLM_FILTER_PROBABILITY_THRESHOLD=0.50
LLM_FILTER_MINIMUM_POSITIVE_SIGNALS=3
LLM_FILTER_MINIMUM_TECHNICAL_SIGNALS=1
```

To permit controlled web research while keeping the empty local-news tool hidden, also configure:

```text
LLM_FORECAST_PROMPT_VERSION=llm-five-session-v6
LLM_RECENT_NEWS_TOOL_ENABLED=false
LLM_MAX_DATABASE_TOOL_CALLS=5
LLM_WEB_RESEARCH_ENABLED=true
OPENAI_WEB_SEARCH_MODEL=gpt-5.5
LLM_WEB_RESEARCH_REASONING_EFFORT=low
LLM_WEB_RESEARCH_CONNECT_TIMEOUT=10s
LLM_WEB_RESEARCH_READ_TIMEOUT=180s
LLM_WEB_RESEARCH_MAX_REQUESTS=5
LLM_WEB_RESEARCH_ALLOWED_DOMAINS=sec.gov,data.sec.gov,federalreserve.gov,bls.gov,bea.gov
LLM_WEB_RESEARCH_CATEGORIES=NEWS,REGULATORY,COMPANY_EVENT,EARNINGS,MACRO
```

Keep the API key in environment variables, never VM arguments, source files, or committed `.env` files. OpenAI calls
use structured output and `store=false`. The model may request at most five database function calls. With
`LLM_RECENT_NEWS_TOOL_ENABLED=false`, `get_recent_news` is not exposed and up to five hosted web-search calls are
available. Setting the flag to `true` exposes stored news and reduces hosted web search to at most one call. These are
upper bounds: the model may use fewer calls when the supplied evidence is sufficient. Batch symbols and second-stage
candidate web searches run concurrently, but all paid-provider traffic is protected by global configurable limits.
The defaults allow four concurrent batch items and OpenAI requests, four concurrent selection web searches, and two
concurrent Sharadar requests. `429`, `503`, connection, and read-timeout failures are retried up to the configured
budget with `Retry-After` support or exponential backoff with jitter. Dependent tool-calling rounds remain sequential.
Start with these defaults and lower `OPENAI_MAX_CONCURRENT_REQUESTS` if the project reports throttling.

### Analyze one stock

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/analyses" `
  -H "Content-Type: application/json" `
  -d '{"symbol":"AAPL","waitForCompletion":true,"maximumWaitSeconds":60,"forceReanalysis":false,"filter":true}'
```

The response is `200 OK` with the full report if it completes within the requested wait. Otherwise it is
`202 Accepted` with an `analysisRunId`; poll it with:

```powershell
curl.exe "http://localhost:8080/api/v1/analyses/{analysisRunId}"
```

History is available at `GET /api/v1/instruments/AAPL/analyses`.
The default call reuses the latest completed analysis from the current UTC date. To deliberately run the providers and
LLM again, set `"forceReanalysis": true`. The response header `X-Analysis-Reused` indicates which path was used.
`filter` defaults to `true`. When enabled, Java applies the same candidate screen used by CSV batches before OpenAI is
called. Set `"filter": false` to send the stock to the LLM regardless of its deterministic candidate score.

### Analyze a CSV

Create a UTF-8 file with one `symbol` column:

```csv
symbol
AAPL
MSFT
NVDA
```

Submit it asynchronously:

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/batches?filter=true" -F "file=@stocks.csv"
```

Batch processing also reuses today's successful analyses by default. Each item reports
`reusedExistingAnalysis: true` when reused. The batch response records the selected `filter` value, and each item
reports `llmForecastStatus`; stocks rejected by Java have `FILTERED_OUT` and are not sent to OpenAI. Independent
symbols execute in parallel up to `BATCH_ANALYSIS_MAX_CONCURRENCY`; the bounded queue and global provider limits stop
an uploaded file or multiple batches from creating unbounded external traffic.

The default Java filter first uses `probabilityUp >= 0.50` when a promoted compatible Java model is available.
Otherwise it requires at least three positive deterministic signals, including at least one technical signal. Signals
currently cover price versus SMA20/SMA50, positive 20-session return, positive relative strength versus SPY/sector,
positive revenue/net-income growth, and positive-versus-negative news keyword counts. The thresholds are configurable
through the `LLM_FILTER_*` environment variables above; this is a cost/coverage gate, not a buy recommendation.

To force every CSV symbol through a new analysis while retaining the Java screen, call:

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/batches?forceReanalysis=true" -F "file=@stocks.csv"
```

To bypass Java filtering and send every successfully analyzed stock to the LLM, call:

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/batches?filter=false" -F "file=@stocks.csv"
```

Use `GET /api/v1/batches/{batchAnalysisId}` for totals and
`GET /api/v1/batches/{batchAnalysisId}/items` for per-symbol status and analysis-run IDs.

Once some or all items have completed, retrieve the available forecasts. New touch-target forecasts are ordered by
`upsideTouchScore - downsideTouchScore`, then by `upsideTouchScore`:

```powershell
curl.exe "http://localhost:8080/api/v1/batches/{batchAnalysisId}/llm-rankings"
```

The ranking response retains the original directional fields and also returns the configured upside/downside target
percentages, touch scores, their spread, and resolved touch outcomes when available. `scoreType` declares
`UNCALIBRATED_LLM_DIRECTION_AND_TOUCH_SCORES_0_TO_100_NOT_PROBABILITIES_OR_TRADE_RECOMMENDATIONS`.
An `upScore` of 50 means balanced directional evidence;
scores of 55 or higher map to `UP`, scores of 45 or lower map to `DOWN`, and the middle range maps to `UNCERTAIN`.

### Ask a second LLM call to select the strongest candidates

After a batch has reached a terminal state, the application can take the highest risk-adjusted touch forecasts for one horizon and
run a separate comparative LLM analysis. This call uses its own `LLM_SELECTION_*` configuration, prompt, model,
token metrics, database-tool budget, persistence tables, and API result. By default it compares the top 15 available
candidates and returns zero to three selections. Returning zero is valid when none has a defensible upside-touch versus
downside-touch setup. The comparative `selectionScore` is not a calibrated probability or a
trade recommendation.

Enable and configure it in IntelliJ VM options:

```text
-DLLM_SELECTION_ENABLED=true
-DLLM_SELECTION_MODEL=gpt-6.1-sol
-DLLM_SELECTION_REASONING_EFFORT=medium
-DLLM_SELECTION_PROMPT_VERSION=llm-top-candidate-selection-v3
-DLLM_SELECTION_CANDIDATE_LIMIT=15
-DLLM_SELECTION_RESULT_LIMIT=3
-DLLM_SELECTION_MAX_DATABASE_TOOL_CALLS=12
-DLLM_SELECTION_MAX_ROUNDS=14
-DLLM_SELECTION_WEB_RESEARCH_ENABLED=true
-DLLM_SELECTION_WEB_RESEARCH_REQUIRED=true
-DLLM_SELECTION_WEB_RESEARCH_MAX_REQUESTS=15
-DLLM_SELECTION_WEB_RESEARCH_MODEL=gpt-6.1-sol
-DLLM_SELECTION_WEB_RESEARCH_REASONING_EFFORT=low
-DLLM_SELECTION_WEB_RESEARCH_ALLOWED_DOMAINS=sec.gov,data.sec.gov,reuters.com,nasdaq.com,nyse.com,federalreserve.gov,bls.gov,bea.gov
```

It uses the existing `OPENAI_API_KEY`. The first forecast call continues to use `OPENAI_MODEL`; the second comparative
call uses `LLM_SELECTION_MODEL`.

Run the five-session comparison:

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/batches/{batchAnalysisId}/llm-selections" `
  -H "Content-Type: application/json" `
  -d '{"horizonTradingSessions":5,"waitForCompletion":true,"maximumWaitSeconds":120,"forceReselection":false}'
```

The default behavior reuses a completed selection for the same batch, horizon, limits, model, and prompt version. Set
`forceReselection` to `true` to create a new immutable selection run. A request made while the batch is still queued or
running returns a conflict instead of ranking partial results. If the HTTP request returns `202 Accepted`, poll:

```powershell
curl.exe "http://localhost:8080/api/v1/llm-selections/{selectionId}"
```

The selector requests one bounded hosted web-search response for every candidate, so the default top-15 comparison can
make up to 15 web-research requests before the final zero-to-three ranking. Searches are sequential to avoid a rate-limit
burst, use the candidate's original information cutoff to prevent look-ahead leakage, and are restricted to configured
trusted domains. A failed search is recorded as a limitation and does not become negative stock evidence. Every source
URL returned by OpenAI is post-validated against the same domain allowlist.

The selector can also request additional point-in-time price, fundamental, market, and sector data only for symbols in
the candidate set. It cannot run arbitrary SQL. PostgreSQL stores the run in `llm_selection_run`, every compared symbol
in `llm_selection_candidate`, each OpenAI round in `llm_selection_interaction`, every database or web tool call in
`llm_selection_tool_call`, and per-symbol search summaries, sources, tokens, latency, and failures in
`llm_selection_web_research`.
Do not select a purchase solely because an item is ranked first. Compare the ranking with data quality, bearish evidence,
news coverage, liquidity, valuation, and the recorded forward performance of the unchanged prompt version.

Forecast history and measured directional performance are available at:

```text
GET /api/v1/instruments/{symbol}/llm-forecasts
GET /api/v1/llm-forecasts/performance
```

Review missing information requested across forecasts, ordered by occurrence count:

```text
GET /api/v1/llm-information-gaps?status=NEW&limit=100
```

Manage an information request after reviewing or implementing its data source:

```http
PATCH /api/v1/llm-information-gaps/{fingerprint}
Content-Type: application/json

{
  "status": "PLANNED",
  "resolutionNotes": "Evaluate an options-data provider"
}
```

Supported workflow states are `NEW`, `UNDER_REVIEW`, `PLANNED`, `AVAILABLE`, and `REJECTED`. Each forecast occurrence,
including resolved searches, is retained in `llm_information_request`; the aggregated backlog is stored in
`llm_information_gap`.

Outcomes are resolved when a later analysis synchronizes the next observed EOD bar for that stock. `UP` and `DOWN`
forecasts receive a correctness result; `UNCERTAIN` forecasts retain the actual direction but are not marked correct or
incorrect. Prompt changes must use a new `LLM_FORECAST_PROMPT_VERSION` so results remain comparable and auditable.

Retry only the failed items after correcting the underlying provider, credential, or data issue:

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/batches/{batchAnalysisId}/retry-failed"
```

The retry endpoint returns `202 Accepted` with a new batch ID. The original batch remains unchanged for auditability.
Retries are rejected while the original batch is queued or running, and when it contains no failed items.

External Sharadar and Benzinga failures are logged with the provider, operation, symbol, requested date range,
HTTP status, and a sanitized provider response (limited to 1,000 characters). Request URLs and credentials are
not logged. Batch processing also logs each failed symbol with its application error code and message.

Metrics are scraped by Prometheus from `/actuator/prometheus`. Direct OTLP metric export is disabled by default
because the local OTLP endpoint is Tempo and accepts traces, not metrics. Set `OTLP_METRICS_ENABLED=true` only
when an OTLP collector with a metrics receiver is configured.

## Training and promoting the Phase 2 model

The trainer uses historical EOD rows already synchronized into local PostgreSQL. Analyze a representative set of
stocks first; training on one favorite stock would be too narrow. SPY must also be present, which normal analysis
requests synchronize automatically for market comparison. SPY is resolved as a Sharadar `funds` instrument and
is loaded only as the market benchmark; it is not included as a model target. The defaults require at least 2,000
complete rows and three equity symbols, but those are only execution minimums—not evidence that the resulting
model is investment-grade.

Build and run the trainer:

```powershell
docker compose --profile training build model-trainer
docker compose --profile training run --rm model-trainer
```

Successful training creates a candidate under `model-artifacts/candidates/` and records its model definition,
artifact hash, walk-forward metrics, baselines, and calibration bands in PostgreSQL. Review the candidate JSON and
the stored evaluation before promotion.

Promote an exact reviewed candidate:

```powershell
docker compose --profile training run --rm model-trainer `
  /app/promote.py /artifacts/candidates/logistic-YYYYMMDDTHHMMSSZ.json
```

The command writes `model-artifacts/champion.json` atomically and marks the model as champion. Java checks the file
for changes before inference, so an IntelliJ restart is not required. The next completed analysis will include a
calibrated `probabilityUp`, the model and calibration versions, the feature snapshot, and the target session.

Prediction history and resolved outcomes:

```powershell
curl.exe "http://localhost:8080/api/v1/instruments/AAPL/predictions"
```

When a later AAPL analysis fetches a new EOD bar, unresolved earlier predictions are matched to the first observed
session after their cutoff. The actual direction, correctness, and Brier component are stored without modifying the
live model.

Set this VM option on the IntelliJ run configuration so the JVM and PostgreSQL use the same canonical timezone:

```text
-Duser.timezone=UTC
```

### Fully containerized mode

```powershell
docker compose --profile containerized-app up --build -d
```

### Local endpoints

| Service | URL |
|---|---|
| Analyzer health | `http://localhost:8080/actuator/health` |
| Analyzer metrics | `http://localhost:8080/actuator/prometheus` |
| Grafana | `http://localhost:3000` |

### Five-session Responses workflow

The current LLM workflow uses the OpenAI Responses API with `store=false`. One stateless conversation produces one
5-session assessment. The application replays typed response items and function outputs between rounds; OpenAI does
not retain the conversation for the application. The forecast includes an uncalibrated directional
`upScore`, an `upsideTouchScore`, and a `downsideTouchScore`. The touch scores need not sum to 100 because both barriers
may be reached within one horizon.

The default five-session target succeeds when the fully adjusted intraday high touches +3% at any point during the next
five completed U.S. trading sessions. Downside risk records whether the fully adjusted intraday low touches -2% during
the same window. Configure the magnitudes with `STOCK_TARGET_UPSIDE_PERCENT` and
`STOCK_TARGET_DOWNSIDE_PERCENT`. Sharadar supplies split-adjusted OHLC and fully adjusted close; the application derives
fully adjusted highs/lows as `high|low * close_fully_adjusted / close_split_adjusted`. Outcomes store first-touch dates,
maximum return, maximum drawdown, fifth-session return, and whether the sequence was `TARGET_FIRST`, `DOWNSIDE_FIRST`,
`SAME_SESSION_AMBIGUOUS`, `TARGET_ONLY`, `DOWNSIDE_ONLY`, or `NEITHER`.

The model can make at most five calls to the read-only database tools (`get_available_stock_data`, `get_price_history`,
`get_fundamental_history`, `get_market_context`, and `get_sector_context`). `get_recent_news` is added only when
`LLM_RECENT_NEWS_TOOL_ENABLED=true`. When it is disabled and web research is enabled, the model can make up to five
hosted web-search calls restricted to the configured domain allowlist. Enabling the local-news tool reduces hosted web
search to one call. Tool inputs are schema constrained, row limited, and fixed to the analyzed instrument and
information cutoff. Arbitrary SQL and arbitrary HTTP access are not exposed.

Both `llmForecast` and the single entry in `llmForecasts` represent the five-session forecast. Batch rankings default
to five sessions:

```text
GET /api/v1/batches/{batchAnalysisId}/llm-rankings?horizon=5
```

PostgreSQL stores one new `llm_forecast` row per analysis. Historical one-session rows remain readable but new analyses
create only horizon-5 rows. `ai_interaction` stores every successful Responses
round and its token/latency totals; `ai_tool_call` stores each database or web-search tool call, status, duration, and
bounded result count. Prometheus exposes OpenAI tokens, response counts and latency, tool counts and latency, filter
outcomes, forecast score distributions, batch outcomes, queued/in-flight work, provider concurrency, transient retries,
and provider latency. Grafana's Application Overview dashboard includes panels for these values.
| Prometheus | `http://localhost:9090` |
| Tempo | `http://localhost:3200` |
| Loki | `http://localhost:3100/ready` |
| Alloy | `http://localhost:12345` |

Default Grafana credentials are `admin` / `admin` and should only be used locally.

### Resolve an instrument

Set `SHARADAR_API_KEY` in your IntelliJ run configuration or local `.env` file. Do not put the key in `application.yml` or commit it.

The first request resolves the symbol through Sharadar and stores its canonical identity. Later requests reuse the stored record:

```powershell
curl.exe -X POST http://localhost:8080/api/v1/instruments/resolve `
  -H "Content-Type: application/json" `
  -d '{"symbol":"AAPL","refresh":false}'
```

Example response:

```json
{
  "instrumentId": "generated-uuid",
  "symbol": "AAPL",
  "name": "APPLE INC",
  "exchange": "NASDAQ",
  "currency": "USD",
  "active": true,
  "sector": "Technology",
  "industry": "Computers",
  "permanentProviderId": "199059",
  "providerLastUpdated": "2026-10-02"
}
```

Use `"refresh": true` only when you intentionally want to refresh the security-master record from Sharadar. It does not yet synchronize price or fundamental history.

### Tests

Run fast unit tests:

```powershell
.\mvnw.cmd test
```

Run unit tests plus the real PostgreSQL/Flyway integration test:

```powershell
.\mvnw.cmd verify -Pintegration-tests
```

The integration profile requires a working Docker engine.

## Persistent research-agent conversations

The personal research agent provides a read-only conversational layer over stored analyses, forecasts, batches,
candidate selections, web-research audits, and resolved forecast performance. It cannot execute arbitrary SQL or
write analysis data. Every message and tool call is persisted in PostgreSQL.

Enable it with these VM arguments:

```text
-DRESEARCH_AGENT_ENABLED=true
-DRESEARCH_AGENT_MODEL=gpt-6.1-sol
-DRESEARCH_AGENT_REASONING_EFFORT=medium
-DRESEARCH_AGENT_SUMMARY_MODEL=gpt-6.1-sol
-DRESEARCH_AGENT_SUMMARY_REASONING_EFFORT=low
-DRESEARCH_AGENT_SUMMARY_THRESHOLD_MESSAGES=20
-DRESEARCH_AGENT_MAX_TOOL_CALLS=12
-DRESEARCH_AGENT_MAX_ROUNDS=14
```

Create a conversation:

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/agent/conversations" `
  -H "Content-Type: application/json" `
  -d '{"title":"Daily stock research"}'
```

Send a message using the returned `conversationId`:

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/agent/conversations/{conversationId}/messages" `
  -H "Content-Type: application/json" `
  -d '{"message":"Which five-session stocks ranked highest in batch 62d08b3a-b675-471b-bdea-2fec2c11cfd8, and why?"}'
```

Read the durable conversation metadata and messages:

```text
GET /api/v1/agent/conversations
GET /api/v1/agent/conversations/{conversationId}
GET /api/v1/agent/conversations/{conversationId}/messages?limit=100
```

The default `RESEARCH_AGENT_SUMMARY_THRESHOLD_MESSAGES=20` means summarization runs after twenty unsummarized stored
messages (normally ten user/assistant exchanges). Original rows are never deleted. The current compact memory is stored
in `agent_conversation.current_summary`, and every version is retained in `agent_conversation_summary`. Later calls send
the current summary plus messages created after that summary, which bounds context growth while preserving a complete
local audit trail.

Available read-only tools are `get_stock_analysis`, `get_stock_forecasts`, `get_batch_status`, `get_batch_rankings`,
`get_selection_result`, and `get_forecast_performance`. Tool arguments and results are recorded in `agent_tool_call`.
