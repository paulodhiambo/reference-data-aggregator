package com.ncbaloop.rdas.controller;

import com.ncbaloop.rdas.service.CountryQueryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(CountryController.CountryNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(
            CountryController.CountryNotFoundException ex, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "Country Not Found", ex.getMessage(), req);
    }

    @ExceptionHandler(CountryQueryService.SnapshotNotReadyException.class)
    public ResponseEntity<ProblemDetail> handleNotReady(
            CountryQueryService.SnapshotNotReadyException ex, HttpServletRequest req) {
        ProblemDetail pd = buildProblem(HttpStatus.SERVICE_UNAVAILABLE,
                "Service Temporarily Unavailable", ex.getMessage(), req);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            ConstraintViolationException ex, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid Request Parameter", ex.getMessage(), req);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        String detail = "Parameter '" + ex.getName() + "' has invalid value: " + ex.getValue();
        return problem(HttpStatus.BAD_REQUEST, "Invalid Request Parameter", detail, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex, HttpServletRequest req) {
        logger.error("Unhandled exception", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred. Please contact support.", req);
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpStatus status, String title, String detail, HttpServletRequest req) {
        ProblemDetail pd = buildProblem(status, title, detail, req);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }

    private ProblemDetail buildProblem(
            HttpStatus status, String title, String detail, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setTitle(title);
        pd.setDetail(detail);
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}
