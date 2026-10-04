package com.stock.analyzer.application.synchronization;

import java.time.LocalDate;
import java.util.List;

public record SyncSummary(
		LocalDate priceCutoff,
		int pricesWritten,
		int fundamentalsWritten,
		int actionsWritten,
		int newsWritten,
		List<String> limitations) {
}
