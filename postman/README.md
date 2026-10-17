# Stock Analyzer Postman workflow

Import `stock-analyzer-e2e.postman_collection.json` into the Postman desktop app.

Before running it:

1. Start PostgreSQL, Prometheus, Grafana, and the Spring application.
2. In Postman, set the working directory to `D:\code_base\AI\analyzer` so the upload request can read
   `postman/sample-50-stocks.csv`.
3. Open the collection's **Variables** tab and confirm `baseUrl` is `http://localhost:8080`.
4. Use **Run collection** and keep the requests in their numbered order.

The runner automatically saves `batchAnalysisId` and `selectionId`. The two polling requests repeat until their jobs
reach a terminal status. The default workflow refreshes missing Sharadar data, applies the Java filter, obtains the
five-session forecasts, researches all 15 second-stage candidates, and asks the selection model for the top three.

To use a different CSV, edit the file selected in request **01 - Submit filtered CSV batch**. A batch needs at least
15 successful, available five-session LLM forecasts for the second-stage selector to research all 15 candidates.

Important collection variables:

- `forceReanalysis=false`: reuse a successful analysis already completed today.
- `filter=true`: apply the deterministic Java filter before first-stage LLM forecasting.
- `forceReselection=true`: create a fresh second-stage run while testing news research.
- `horizonTradingSessions=5`: rank the five-session forecasts.
- `pollDelayMs=3000`: wait three seconds between status checks in Collection Runner.

The polling scripts use Postman's collection-runner flow control. When requests are sent manually, click **Send** on
the polling request again until the returned status is terminal.
