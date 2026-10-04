package com.stock.analyzer.analysis.domain;

import java.util.List;

public record NarrativeOutput(
		String overallObservation,
		List<String> fundamentalObservations,
		List<String> peerAndSectorObservations,
		List<String> technicalObservations,
		List<String> marketObservations,
		List<String> newsObservations,
		List<String> supportingEvidence,
		List<String> opposingEvidence,
		List<String> conflictingEvidence,
		List<String> missingInformation,
		List<String> dataLimitations,
		List<String> whatToMonitor) {
}
