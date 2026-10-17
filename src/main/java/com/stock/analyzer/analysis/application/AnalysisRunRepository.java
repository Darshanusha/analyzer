package com.stock.analyzer.analysis.application;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.stock.analyzer.analysis.domain.AnalysisComponentResult;
import com.stock.analyzer.analysis.domain.AnalysisReport;
import com.stock.analyzer.analysis.domain.AnalysisRunState;
import com.stock.analyzer.analysis.domain.NarrativeGeneration;
import com.stock.analyzer.instrument.domain.Instrument;

public interface AnalysisRunRepository {

	UUID create(Instrument instrument, String requestedSymbol);

	void markRunning(UUID analysisRunId);

	void saveComponent(UUID analysisRunId, AnalysisComponentResult component);

	void recordNarrativeInvocation(UUID analysisRunId, NarrativeGeneration generation);

	void complete(UUID analysisRunId, AnalysisReport report, LocalDate priceCutoff,
			String dataQualityStatus, NarrativeGeneration narrative, long durationMs);

	void fail(UUID analysisRunId, String errorCode, String errorMessage, long durationMs);

	Optional<AnalysisRunState> find(UUID analysisRunId);

	Optional<AnalysisRunState> findCompletedForDay(UUID instrumentId, Instant dayStart, Instant nextDayStart);

	List<AnalysisRunState> findBySymbol(String symbol, int limit);
}
