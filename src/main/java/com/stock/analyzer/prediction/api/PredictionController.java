package com.stock.analyzer.prediction.api;

import java.util.List;

import com.stock.analyzer.prediction.application.PredictionService;
import com.stock.analyzer.prediction.domain.PredictionResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/instruments")
public class PredictionController {

	private final PredictionService predictionService;

	public PredictionController(PredictionService predictionService) {
		this.predictionService = predictionService;
	}

	@GetMapping("/{symbol}/predictions")
	public List<PredictionResult> history(@PathVariable String symbol,
			@RequestParam(defaultValue = "20") int limit) {
		return predictionService.history(symbol, limit);
	}
}
