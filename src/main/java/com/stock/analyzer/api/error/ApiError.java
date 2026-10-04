package com.stock.analyzer.api.error;

import java.time.Instant;
import java.util.List;

public record ApiError(
		Instant timestamp,
		int status,
		String code,
		String message,
		String path,
		String correlationId,
		List<FieldViolation> violations) {

	public record FieldViolation(String field, String message) {
	}
}
