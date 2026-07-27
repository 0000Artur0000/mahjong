package ru.dorahub.system.internal.http;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
final class ApiProblemHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(ApiProblemHandler.class);

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    var problem = exception.getBody();
    problem.setProperty(
        "fieldErrors",
        exception.getBindingResult().getFieldErrors().stream().map(this::fieldError).toList());
    return handleExceptionInternal(exception, problem, headers, status, request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
    LOG.error(
        "Unhandled request error [{}]: {}", correlationId(request), exception.getClass().getName());
    var problem =
        ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    return handleExceptionInternal(
        exception, problem, HttpHeaders.EMPTY, HttpStatus.INTERNAL_SERVER_ERROR, request);
  }

  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    if (body instanceof ProblemDetail problem) {
      var code = codeFor(statusCode.value());
      problem.setType(URI.create("urn:dorahub:problem:" + code));
      problem.setProperty("code", code);
      problem.setProperty("correlationId", correlationId(request));
    }
    return super.createResponseEntity(body, headers, statusCode, request);
  }

  private Map<String, String> fieldError(FieldError error) {
    return Map.of(
        "field", error.getField(),
        "code", error.getCode() == null ? "Invalid" : error.getCode(),
        "message", error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage());
  }

  private String correlationId(WebRequest request) {
    if (request instanceof ServletWebRequest servletRequest) {
      var value = servletRequest.getRequest().getAttribute(CorrelationIdFilter.ATTRIBUTE);
      if (value instanceof String id) {
        return id;
      }
    }
    return UUID.randomUUID().toString();
  }

  private String codeFor(int status) {
    return switch (status) {
      case 400 -> "request.invalid";
      case 401 -> "authentication.required";
      case 403 -> "access.denied";
      case 404 -> "resource.not_found";
      case 409 -> "state.conflict";
      case 413 -> "request.too_large";
      case 422 -> "request.unprocessable";
      case 429 -> "rate_limit.exceeded";
      case 503 -> "service.unavailable";
      default -> status >= 500 ? "internal.error" : "request.failed";
    };
  }
}
