(() => {
    'use strict';

    const state = { batchId: null, selectionId: null, batchTimer: null, selectionTimer: null };
    const terminal = new Set(['COMPLETED', 'PARTIAL_SUCCESS', 'FAILED']);
    const DEFAULT_POLL_SECONDS = 30;
    const MIN_POLL_SECONDS = 5;
    const MAX_POLL_SECONDS = 300;
    const $ = (id) => document.getElementById(id);

    function pollIntervalSeconds() {
        const value = Number.parseInt($('pollIntervalSeconds')?.value, 10);
        return Number.isFinite(value)
            ? Math.max(MIN_POLL_SECONDS, Math.min(value, MAX_POLL_SECONDS))
            : DEFAULT_POLL_SECONDS;
    }

    function configurePollInterval() {
        const input = $('pollIntervalSeconds');
        let saved = DEFAULT_POLL_SECONDS;
        try { saved = Number.parseInt(window.localStorage.getItem('stockAnalyzer.pollIntervalSeconds'), 10); }
        catch (_) { /* Browser storage is optional. */ }
        input.value = Number.isFinite(saved)
            ? Math.max(MIN_POLL_SECONDS, Math.min(saved, MAX_POLL_SECONDS))
            : DEFAULT_POLL_SECONDS;
        input.addEventListener('change', () => {
            input.value = pollIntervalSeconds();
            try { window.localStorage.setItem('stockAnalyzer.pollIntervalSeconds', input.value); }
            catch (_) { /* Keep the runtime value when storage is unavailable. */ }
            if (state.batchTimer) startBatchPolling();
            if (state.selectionTimer) startSelectionPolling();
            toast(`Polling interval changed to ${input.value} seconds`);
        });
    }

    async function api(path, options = {}) {
        const response = await fetch(path, options);
        const contentType = response.headers.get('content-type') || '';
        const body = contentType.includes('application/json') ? await response.json() : await response.text();
        if (!response.ok) {
            const message = body?.message || body?.errorMessage || (typeof body === 'string' ? body : response.statusText);
            throw new Error(message || `Request failed with HTTP ${response.status}`);
        }
        return body;
    }

    function toast(message, error = false) {
        const node = $('toast');
        node.textContent = message;
        node.className = error ? 'show error' : 'show';
        window.clearTimeout(node._timer);
        node._timer = window.setTimeout(() => { node.className = ''; }, 4200);
    }

    function busy(button, enabled, label) {
        if (!button) return;
        if (!button.dataset.label) button.dataset.label = button.textContent;
        button.disabled = enabled;
        button.textContent = enabled ? label : button.dataset.label;
    }

    function statusClass(value) {
        return String(value || '').toLowerCase().replaceAll(' ', '_');
    }

    function td(row, value, className) {
        const cell = document.createElement('td');
        cell.textContent = value ?? '—';
        if (className) cell.className = className;
        row.appendChild(cell);
        return cell;
    }

    function formatScore(value) {
        return value === null || value === undefined ? '—' : `${value}`;
    }

    function showJson(target, value) {
        target.replaceChildren();
        const pre = document.createElement('pre');
        pre.textContent = JSON.stringify(value, null, 2);
        target.appendChild(pre);
        target.classList.remove('hidden');
    }

    $('singleForm').addEventListener('submit', async (event) => {
        event.preventDefault();
        const button = event.submitter;
        busy(button, true, 'Analyzing…');
        try {
            const result = await api('/api/v1/analyses', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    symbol: $('symbol').value.trim().toUpperCase(),
                    waitForCompletion: true,
                    maximumWaitSeconds: 120,
                    forceReanalysis: $('singleForce').checked,
                    filter: $('singleFilter').checked
                })
            });
            showJson($('singleResult'), result);
            toast(`Analysis ${result.status || 'submitted'}`);
        } catch (error) {
            toast(error.message, true);
        } finally {
            busy(button, false);
        }
    });

    $('csvFile').addEventListener('change', () => {
        $('fileLabel').textContent = $('csvFile').files[0]?.name || 'Choose a CSV file';
    });
    const drop = $('fileDrop');
    ['dragenter', 'dragover'].forEach(name => drop.addEventListener(name, (event) => {
        event.preventDefault(); drop.classList.add('dragging');
    }));
    ['dragleave', 'drop'].forEach(name => drop.addEventListener(name, (event) => {
        event.preventDefault(); drop.classList.remove('dragging');
    }));
    drop.addEventListener('drop', (event) => {
        const file = event.dataTransfer.files[0];
        if (!file) return;
        const transfer = new DataTransfer(); transfer.items.add(file);
        $('csvFile').files = transfer.files; $('fileLabel').textContent = file.name;
    });

    $('batchForm').addEventListener('submit', async (event) => {
        event.preventDefault();
        const file = $('csvFile').files[0];
        if (!file) return toast('Choose a CSV file first', true);
        const button = event.submitter;
        busy(button, true, 'Starting…');
        try {
            const data = new FormData();
            data.append('file', file);
            const query = new URLSearchParams({
                filter: $('batchFilter').checked,
                forceReanalysis: $('batchForce').checked
            });
            const batch = await api(`/api/v1/batches?${query}`, { method: 'POST', body: data });
            state.batchId = batch.batchAnalysisId;
            $('batchWorkspace').classList.remove('hidden');
            $('resultsWorkspace').classList.remove('hidden');
            updateBatch(batch);
            await loadItems();
            startBatchPolling();
            $('batchWorkspace').scrollIntoView({ behavior: 'smooth', block: 'start' });
            toast('Batch submitted');
        } catch (error) {
            toast(error.message, true);
        } finally {
            busy(button, false);
        }
    });

    function updateBatch(batch) {
        state.batchId = batch.batchAnalysisId;
        $('copyBatchId').textContent = state.batchId;
        $('batchStatus').textContent = batch.status;
        $('batchStatus').className = `status ${statusClass(batch.status)}`;
        $('completedCount').textContent = `${batch.completedItems} / ${batch.totalItems}`;
        $('successCount').textContent = batch.successfulItems;
        $('failedCount').textContent = batch.failedItems;
        $('progressBar').style.width = `${batch.totalItems ? (batch.completedItems / batch.totalItems) * 100 : 0}%`;
        $('retryFailed').classList.toggle('hidden', !(terminal.has(batch.status) && batch.failedItems > 0));
        if (terminal.has(batch.status)) {
            window.clearInterval(state.batchTimer);
            state.batchTimer = null;
        }
    }

    async function refreshBatch() {
        if (!state.batchId) return;
        const batch = await api(`/api/v1/batches/${state.batchId}`);
        updateBatch(batch);
        await loadItems();
        if (terminal.has(batch.status)) await loadRankings();
    }

    async function loadItems() {
        if (!state.batchId) return;
        const items = await api(`/api/v1/batches/${state.batchId}/items`);
        const tbody = $('batchItems'); tbody.replaceChildren();
        items.forEach(item => {
            const row = document.createElement('tr');
            td(row, item.symbol);
            td(row, item.status);
            td(row, item.llmForecastStatus);
            td(row, item.errorMessage || item.errorCode, item.errorMessage || item.errorCode ? 'error-cell' : '');
            tbody.appendChild(row);
        });
    }

    function startBatchPolling() {
        window.clearInterval(state.batchTimer);
        state.batchTimer = window.setInterval(() => refreshBatch().catch(error => toast(error.message, true)),
                pollIntervalSeconds() * 1000);
    }

    $('refreshBatch').addEventListener('click', () => refreshBatch().catch(error => toast(error.message, true)));
    $('retryFailed').addEventListener('click', async (event) => {
        const button = event.currentTarget;
        busy(button, true, 'Retrying…');
        try {
            const batch = await api(`/api/v1/batches/${state.batchId}/retry-failed`, { method: 'POST' });
            updateBatch(batch); startBatchPolling(); toast('Failed items queued again');
        } catch (error) { toast(error.message, true); }
        finally { busy(button, false); }
    });

    async function loadRankings() {
        if (!state.batchId) return toast('Start or load a batch first', true);
        const result = await api(`/api/v1/batches/${state.batchId}/llm-rankings?horizon=5`);
        const tbody = $('rankings'); tbody.replaceChildren();
        result.rankings.forEach(item => {
            const row = document.createElement('tr');
            td(row, item.rank);
            td(row, item.symbol);
            td(row, formatScore(item.upsideTouchScore), 'score positive');
            td(row, formatScore(item.downsideTouchScore), 'score negative');
            const spread = td(row, formatScore(item.touchScoreSpread), 'score');
            if (item.touchScoreSpread > 0) spread.classList.add('positive');
            if (item.touchScoreSpread < 0) spread.classList.add('negative');
            td(row, item.confidence);
            td(row, item.targetSession);
            row.title = item.thesis || '';
            tbody.appendChild(row);
        });
        toast(`Loaded ${result.rankings.length} ranked forecasts`);
    }
    $('loadRankings').addEventListener('click', () => loadRankings().catch(error => toast(error.message, true)));
    $('toggleRankings').addEventListener('click', (event) => {
        const button = event.currentTarget;
        const expanded = button.getAttribute('aria-expanded') === 'true';
        const nextExpanded = !expanded;
        button.setAttribute('aria-expanded', String(nextExpanded));
        button.title = nextExpanded ? 'Collapse stock rankings' : 'Expand stock rankings';
        button.querySelector('.sr-only').textContent = button.title;
        $('rankingsList').classList.toggle('hidden', !nextExpanded);
    });

    $('runSelection').addEventListener('click', async (event) => {
        if (!state.batchId) return toast('Start or load a batch first', true);
        const button = event.currentTarget;
        busy(button, true, 'Selecting…');
        state.selectionId = null;
        $('copySelectionId').textContent = 'pending';
        showSelectionProgress('Submitting the candidates for second-level LLM analysis…');
        try {
            const selection = await api(`/api/v1/batches/${state.batchId}/llm-selections`, {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ horizonTradingSessions: 5, waitForCompletion: false, forceReselection: false })
            });
            showSelection(selection);
            startSelectionPolling();
            $('selectionWorkspace').scrollIntoView({ behavior: 'smooth', block: 'start' });
        } catch (error) {
            $('selectionSummary').textContent = 'Selection could not be started';
            $('selectedCandidates').replaceChildren();
            toast(error.message, true);
        }
        finally { busy(button, false); }
    });

    function showSelectionProgress(message) {
        $('selectionWorkspace').classList.remove('hidden');
        $('selectionSummary').textContent = message;
        const grid = $('selectedCandidates');
        grid.replaceChildren();
        const progress = document.createElement('div');
        progress.className = 'selection-progress';
        progress.setAttribute('role', 'status');
        progress.setAttribute('aria-live', 'polite');
        const spinner = document.createElement('span');
        spinner.className = 'spinner';
        spinner.setAttribute('aria-hidden', 'true');
        const text = document.createElement('span');
        text.textContent = message;
        progress.append(spinner, text);
        grid.appendChild(progress);
        $('selectionWorkspace').scrollIntoView({ behavior: 'smooth', block: 'start' });
    }

    function showSelection(selection) {
        state.selectionId = selection.selectionId;
        $('selectionWorkspace').classList.remove('hidden');
        $('copySelectionId').textContent = state.selectionId;
        $('selectionSummary').textContent = `${selection.status} · ${selection.selectedCount || 0} selected from ${selection.candidateCount || 0} · ${selection.totalTokens || 0} tokens · ${selection.durationMs || 0} ms`;
        const grid = $('selectedCandidates'); grid.replaceChildren();
        if (!terminal.has(selection.status)) {
            showSelectionProgress(`Second-level LLM analysis is ${String(selection.status || 'in progress').toLowerCase()}…`);
            return;
        }
        (selection.candidates || []).filter(item => item.selected).sort((a, b) => a.selectedRank - b.selectedRank).forEach(item => {
            const card = document.createElement('article'); card.className = 'candidate';
            const heading = document.createElement('h3');
            const symbol = document.createElement('span'); symbol.textContent = item.symbol;
            const score = document.createElement('strong'); score.textContent = item.selectionScore ?? '—';
            heading.append(symbol, score); card.appendChild(heading);
            const thesis = document.createElement('p'); thesis.textContent = item.comparativeThesis || 'No thesis supplied.';
            card.appendChild(thesis);
            const list = document.createElement('ul');
            (item.invalidationRisks || []).slice(0, 3).forEach(value => { const li = document.createElement('li'); li.textContent = value; list.appendChild(li); });
            if (list.children.length) card.appendChild(list);
            grid.appendChild(card);
        });
    }

    async function refreshSelection() {
        if (!state.selectionId) return;
        const selection = await api(`/api/v1/llm-selections/${state.selectionId}`);
        showSelection(selection);
        if (terminal.has(selection.status)) { window.clearInterval(state.selectionTimer); state.selectionTimer = null; }
    }
    function startSelectionPolling() {
        window.clearInterval(state.selectionTimer);
        state.selectionTimer = window.setInterval(() => refreshSelection().catch(error => toast(error.message, true)),
                pollIntervalSeconds() * 1000);
    }
    $('refreshSelection').addEventListener('click', () => refreshSelection().catch(error => toast(error.message, true)));

    [['copyBatchId', () => state.batchId], ['copySelectionId', () => state.selectionId]].forEach(([id, value]) => {
        $(id).addEventListener('click', async () => {
            if (!value()) return;
            await navigator.clipboard.writeText(value()); toast('ID copied');
        });
    });

    window.addEventListener('unhandledrejection', event => {
        $('connectionState').classList.add('error');
        toast(event.reason?.message || 'Unexpected browser error', true);
    });

    configurePollInterval();
})();
