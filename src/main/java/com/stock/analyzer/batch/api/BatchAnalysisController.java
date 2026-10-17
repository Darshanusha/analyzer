package com.stock.analyzer.batch.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.stock.analyzer.api.error.ApiException;
import com.stock.analyzer.batch.application.BatchAnalysisService;
import com.stock.analyzer.batch.domain.BatchAnalysisItemState;
import com.stock.analyzer.batch.domain.BatchAnalysisState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/batches")
public class BatchAnalysisController {

	private static final int MAX_SYMBOLS = 600;
	private static final Pattern SUPPORTED_SYMBOL = Pattern.compile("[A-Z][A-Z0-9.-]{0,14}");

	private final BatchAnalysisService service;

	public BatchAnalysisController(BatchAnalysisService service) {
		this.service = service;
	}

	@PostMapping
	public ResponseEntity<BatchAnalysisState> create(@RequestPart("file") MultipartFile file,
			@RequestParam(defaultValue = "false") boolean forceReanalysis,
			@RequestParam(defaultValue = "true") boolean filter) {
		List<String> symbols = parse(file);
		return ResponseEntity.accepted().body(service.submit(symbols, forceReanalysis, filter));
	}

	@GetMapping("/{batchAnalysisId}")
	public BatchAnalysisState get(@PathVariable UUID batchAnalysisId) {
		return service.get(batchAnalysisId);
	}

	@GetMapping("/{batchAnalysisId}/items")
	public List<BatchAnalysisItemState> items(@PathVariable UUID batchAnalysisId) {
		return service.items(batchAnalysisId);
	}

	@PostMapping("/{batchAnalysisId}/retry-failed")
	public ResponseEntity<BatchAnalysisState> retryFailed(@PathVariable UUID batchAnalysisId) {
		return ResponseEntity.accepted().body(service.retryFailed(batchAnalysisId));
	}

	private List<String> parse(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw badCsv("CSV file is required");
		}
		Set<String> symbols = new LinkedHashSet<>();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
			String header = reader.readLine();
			if (header == null || !"symbol".equalsIgnoreCase(header.replace("\uFEFF", "").strip())) {
				throw badCsv("CSV must contain a single 'symbol' header");
			}
			String line;
			int lineNumber = 1;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				String value = unquote(line.strip()).toUpperCase(Locale.ROOT);
				if (value.isBlank()) continue;
				if (!SUPPORTED_SYMBOL.matcher(value).matches()) {
					throw badCsv("Invalid symbol on CSV line " + lineNumber);
				}
				symbols.add(value);
				if (symbols.size() > MAX_SYMBOLS) {
					throw badCsv("CSV may contain at most " + MAX_SYMBOLS + " unique symbols");
				}
			}
		}
		catch (IOException exception) {
			throw badCsv("CSV file could not be read");
		}
		if (symbols.isEmpty()) throw badCsv("CSV contains no symbols");
		return List.copyOf(symbols);
	}

	private String unquote(String value) {
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			return value.substring(1, value.length() - 1).replace("\"\"", "\"").strip();
		}
		return value;
	}

	private ApiException badCsv(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CSV", message);
	}
}
