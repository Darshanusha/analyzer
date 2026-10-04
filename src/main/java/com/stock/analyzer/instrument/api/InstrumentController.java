package com.stock.analyzer.instrument.api;

import jakarta.validation.Valid;

import com.stock.analyzer.instrument.application.InstrumentResolver;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/instruments")
public class InstrumentController {

	private final InstrumentResolver instrumentResolver;

	public InstrumentController(InstrumentResolver instrumentResolver) {
		this.instrumentResolver = instrumentResolver;
	}

	@PostMapping("/resolve")
	@ResponseStatus(HttpStatus.OK)
	InstrumentResponse resolve(@Valid @RequestBody ResolveInstrumentRequest request) {
		return InstrumentResponse.from(instrumentResolver.resolve(request.symbol(), request.refresh()));
	}
}
