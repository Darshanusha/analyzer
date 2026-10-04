package com.stock.analyzer.observability;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTests {

	private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

	@Test
	void preservesValidCorrelationIdDuringRequestAndReturnsItInResponse() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(RequestCorrelationFilter.CORRELATION_HEADER, "analysis-request-123");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> correlationIdDuringRequest = new AtomicReference<>();

		filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> correlationIdDuringRequest
				.set(MDC.get(RequestCorrelationFilter.CORRELATION_MDC_KEY)));

		assertThat(correlationIdDuringRequest).hasValue("analysis-request-123");
		assertThat(response.getHeader(RequestCorrelationFilter.CORRELATION_HEADER))
				.isEqualTo("analysis-request-123");
		assertThat(MDC.get(RequestCorrelationFilter.CORRELATION_MDC_KEY)).isNull();
	}

	@Test
	void replacesUnsafeCorrelationId() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.addHeader(RequestCorrelationFilter.CORRELATION_HEADER, "unsafe value with spaces");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> { });

		assertThat(response.getHeader(RequestCorrelationFilter.CORRELATION_HEADER))
				.matches("[0-9a-f-]{36}");
	}
}
