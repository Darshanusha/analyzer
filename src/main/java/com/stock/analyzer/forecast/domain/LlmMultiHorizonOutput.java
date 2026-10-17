package com.stock.analyzer.forecast.domain;

import java.util.List;

public record LlmMultiHorizonOutput(List<LlmHorizonForecastOutput> forecasts) {
	public LlmMultiHorizonOutput { forecasts = forecasts == null ? List.of() : List.copyOf(forecasts); }
}
