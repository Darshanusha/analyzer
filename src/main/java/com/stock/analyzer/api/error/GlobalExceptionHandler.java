package com.stock.analyzer.api.error;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.stock.analyzer.observability.RequestCorrelationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiError> handleApiException(ApiException exception, HttpServletRequest request) {
		return buildResponse(exception.status(), exception.code(), exception.getMessage(), request, List.of());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
		List<ApiError.FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
				.map(error -> new ApiError.FieldViolation(error.getField(), error.getDefaultMessage()))
				.toList();
		return buildResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request,
				violations);
	}

	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception,
			HttpServletRequest request) {
		String supportedMethods = exception.getSupportedHttpMethods() == null
				? "a supported HTTP method"
				: exception.getSupportedHttpMethods().toString();
		String message = "HTTP method %s is not supported for this endpoint; use %s"
				.formatted(exception.getMethod(), supportedMethods);
		return buildResponse(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", message, request, List.of());
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<ApiError> handleResourceNotFound(NoResourceFoundException exception, HttpServletRequest request) {
		return buildResponse(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
				"The requested resource was not found", request, List.of());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ResponseEntity<ApiError> handleMalformedRequest(HttpMessageNotReadableException exception,
			HttpServletRequest request) {
		return buildResponse(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
				"Request body is missing or contains invalid JSON", request, List.of());
	}

	@ExceptionHandler(ErrorResponseException.class)
	ResponseEntity<ApiError> handleFrameworkError(ErrorResponseException exception, HttpServletRequest request) {
		String message = exception.getBody().getDetail();
		if (message == null || message.isBlank()) {
			message = "The request could not be completed";
		}
		return buildResponse(exception.getStatusCode().value(), "REQUEST_FAILED", message, request, List.of());
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
		log.error("Unhandled request failure", exception);
		return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
				"An unexpected error occurred", request, List.of());
	}

	private ResponseEntity<ApiError> buildResponse(HttpStatus status, String code, String message,
			HttpServletRequest request, List<ApiError.FieldViolation> violations) {
		return buildResponse(status.value(), code, message, request, violations);
	}

	private ResponseEntity<ApiError> buildResponse(int status, String code, String message,
			HttpServletRequest request, List<ApiError.FieldViolation> violations) {
		ApiError error = new ApiError(
				Instant.now(),
				status,
				code,
				message,
				request.getRequestURI(),
				MDC.get(RequestCorrelationFilter.CORRELATION_MDC_KEY),
				violations);
		return ResponseEntity.status(status).body(error);
	}
}
