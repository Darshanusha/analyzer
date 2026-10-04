package com.stock.analyzer.prediction.domain;

public record ModelInference(
		Double probabilityUp,
		String status,
		String modelVersion,
		String calibrationVersion) {
}
