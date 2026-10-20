package com.stock.analyzer.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Shared definition of the price-touch objective used by both LLM forecast stages. */
@ConfigurationProperties("analysis.touch-target")
public class TouchTargetProperties {

	private double upsidePercent = 2.0;
	private double downsidePercent = 2.0;

	public double getUpsidePercent() {
		return upsidePercent;
	}

	public void setUpsidePercent(double upsidePercent) {
		this.upsidePercent = upsidePercent;
	}

	public double getDownsidePercent() {
		return downsidePercent;
	}

	public void setDownsidePercent(double downsidePercent) {
		this.downsidePercent = downsidePercent;
	}

	public void validate() {
		if (!Double.isFinite(upsidePercent) || upsidePercent <= 0 || upsidePercent > 50) {
			throw new IllegalStateException("STOCK_TARGET_UPSIDE_PERCENT must be greater than 0 and at most 50");
		}
		if (!Double.isFinite(downsidePercent) || downsidePercent <= 0 || downsidePercent > 50) {
			throw new IllegalStateException("STOCK_TARGET_DOWNSIDE_PERCENT must be greater than 0 and at most 50");
		}
	}
}
