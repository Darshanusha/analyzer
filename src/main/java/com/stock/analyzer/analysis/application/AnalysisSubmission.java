package com.stock.analyzer.analysis.application;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.stock.analyzer.analysis.domain.AnalysisReport;

public record AnalysisSubmission(
		UUID analysisRunId,
		CompletableFuture<AnalysisReport> completion,
		boolean reusedExistingAnalysis) {

	public AnalysisSubmission(UUID analysisRunId, CompletableFuture<AnalysisReport> completion) {
		this(analysisRunId, completion, false);
	}
}
