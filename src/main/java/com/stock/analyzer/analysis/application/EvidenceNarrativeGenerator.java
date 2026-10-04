package com.stock.analyzer.analysis.application;

import java.util.ArrayList;
import java.util.List;

import com.stock.analyzer.analysis.domain.AnalysisComponentResult;
import com.stock.analyzer.analysis.domain.NarrativeGeneration;
import com.stock.analyzer.analysis.domain.NarrativeOutput;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class EvidenceNarrativeGenerator {

	private final String configuredModel;

	public EvidenceNarrativeGenerator(Environment environment) {
		this.configuredModel = environment.getProperty("spring.ai.openai.chat.options.model", "not-configured");
	}

	/**
	 * Phase 1 keeps narrative generation local until external evidence transmission
	 * is explicitly enabled. The deterministic components remain the source of truth.
	 */
	public NarrativeGeneration generate(String symbol, List<AnalysisComponentResult> components) {
		long started = System.nanoTime();
		NarrativeOutput output = fallback(symbol, components);
		return new NarrativeGeneration(output, "DETERMINISTIC_FALLBACK", "OPENAI", configuredModel,
				"SKIPPED_NOT_ENABLED", null, null, null, elapsedMillis(started), null);
	}

	private NarrativeOutput fallback(String symbol, List<AnalysisComponentResult> components) {
		List<String> fundamentals = summaries(components, "fundamental.");
		List<String> technical = summaries(components, "technical.");
		List<String> market = summaries(components, "market.");
		List<String> news = summaries(components, "news.");
		List<String> evidence = components.stream().flatMap(component -> component.evidence().stream()).limit(12).toList();
		List<String> reasons = components.stream().flatMap(component -> component.reasons().stream()).limit(20).toList();
		List<String> limitations = components.stream().flatMap(component -> component.limitations().stream()).distinct().toList();
		boolean probabilityAvailable = components.stream()
				.anyMatch(component -> "prediction.next-session".equals(component.key())
						&& "AVAILABLE".equals(component.status()));
		return new NarrativeOutput(
				probabilityAvailable
						? "Deterministic evidence summary for " + symbol + "; a separately trained calibrated probability is included."
						: "Deterministic evidence summary for " + symbol + "; no promoted probability model is active.",
				fundamentals.stream().filter(value -> !value.startsWith("fundamental.peer-sector:")).toList(),
				fundamentals.stream().filter(value -> value.startsWith("fundamental.peer-sector:")).toList(),
				technical, market, news, evidence, reasons, List.of(), limitations, limitations,
				List.of("Review the next completed EOD price, material news, and the next financial report"));
	}

	private List<String> summaries(List<AnalysisComponentResult> components, String prefix) {
		List<String> values = new ArrayList<>();
		for (AnalysisComponentResult component : components) {
			if (component.key().startsWith(prefix)) {
				values.add(component.key() + ": " + component.summary());
			}
		}
		return values;
	}

	private long elapsedMillis(long started) {
		return (System.nanoTime() - started) / 1_000_000L;
	}
}
