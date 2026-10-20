package com.stock.analyzer.forecast.domain;

import java.time.LocalDate;
import java.util.UUID;

public record BatchLlmRankingItem(
		int rank,
		String symbol,
		UUID analysisRunId,
		String direction,
		int upScore,
		String confidence,
		String thesis,
		LocalDate targetSession,
		String dataQualityStatus,
		String model,
		String promptVersion,
		Boolean actualUp,
		Boolean correct,
		Integer upsideTouchScore,
		Integer downsideTouchScore,
		Integer touchScoreSpread,
		Double upsideThresholdPercent,
		Double downsideThresholdPercent,
		Boolean upsideTargetTouched,
		Boolean downsideTargetTouched,
		String touchSequence) {
}
