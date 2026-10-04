package com.stock.analyzer.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

	public static final String CORRELATION_HEADER = "X-Correlation-ID";
	public static final String CORRELATION_MDC_KEY = "correlationId";

	private static final Pattern VALID_CORRELATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String correlationId = resolveCorrelationId(request.getHeader(CORRELATION_HEADER));
		response.setHeader(CORRELATION_HEADER, correlationId);
		try (MDC.MDCCloseable ignored = MDC.putCloseable(CORRELATION_MDC_KEY, correlationId)) {
			filterChain.doFilter(request, response);
		}
	}

	private String resolveCorrelationId(String suppliedCorrelationId) {
		if (suppliedCorrelationId != null && VALID_CORRELATION_ID.matcher(suppliedCorrelationId).matches()) {
			return suppliedCorrelationId;
		}
		return UUID.randomUUID().toString();
	}
}
