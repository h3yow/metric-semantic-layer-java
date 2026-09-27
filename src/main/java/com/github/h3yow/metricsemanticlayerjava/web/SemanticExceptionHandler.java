package com.github.h3yow.metricsemanticlayerjava.web;

import com.github.h3yow.metricsemanticlayerjava.exception.MetricSemanticException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps semantic-layer failures to HTTP 400 with a stable, distinguishable
 * {@code code} (matching the exception class), so malformed definitions/queries
 * are never reported as opaque 500s.
 */
@RestControllerAdvice
public class SemanticExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SemanticExceptionHandler.class);

    @ExceptionHandler(MetricSemanticException.class)
    public ResponseEntity<Map<String, Object>> handle(MetricSemanticException ex) {
        log.info("[HTTP拒绝] code={} message={}", ex.code(), ex.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.code());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
