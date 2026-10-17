package com.stock.analyzer.forecast.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CreateLlmSelectionRequest(
        @Min(1) @Max(5) Integer horizonTradingSessions,
        Boolean waitForCompletion,
        @Min(1) @Max(120) Integer maximumWaitSeconds,
        Boolean forceReselection) {

    public int resolvedHorizonTradingSessions() {
        return horizonTradingSessions == null ? 5 : horizonTradingSessions;
    }

    public boolean resolvedWaitForCompletion() {
        return waitForCompletion == null || waitForCompletion;
    }

    public boolean resolvedForceReselection() {
        return Boolean.TRUE.equals(forceReselection);
    }
}
