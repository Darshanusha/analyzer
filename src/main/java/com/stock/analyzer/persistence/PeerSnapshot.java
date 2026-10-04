package com.stock.analyzer.persistence;

import java.math.BigDecimal;

public record PeerSnapshot(
		String symbol,
		String sector,
		String industry,
		BigDecimal grossMargin,
		BigDecimal netMargin,
		BigDecimal returnOnEquity,
		BigDecimal priceEarnings,
		BigDecimal enterpriseValueEbitda,
		BigDecimal marketCap) {
}
