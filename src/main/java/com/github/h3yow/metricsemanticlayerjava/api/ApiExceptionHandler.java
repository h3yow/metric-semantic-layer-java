package com.github.h3yow.metricsemanticlayerjava.api;

import com.github.h3yow.metricsemanticlayerjava.error.CircularDependencyException;
import com.github.h3yow.metricsemanticlayerjava.error.DefinitionException;
import com.github.h3yow.metricsemanticlayerjava.error.MalformedExpressionException;
import com.github.h3yow.metricsemanticlayerjava.error.MetricNotFoundException;
import com.github.h3yow.metricsemanticlayerjava.error.SelfReferenceException;
import com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException;
import com.github.h3yow.metricsemanticlayerjava.error.UnknownFieldException;
import com.github.h3yow.metricsemanticlayerjava.error.VersionNotFoundException;
import com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把彼此可区分的定义异常映射为可区分的错误码与 HTTP 状态，
 * 调用方可以据 {@code errorType} 做不同处理，而不是统一 500。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DefinitionException.class)
    public ResponseEntity<Map<String, Object>> handle(DefinitionException ex) {
        HttpStatus status = switch (ex) {
            case MetricNotFoundException e -> HttpStatus.NOT_FOUND;
            case VersionNotFoundException e -> HttpStatus.NOT_FOUND;
            case UnknownFieldException e -> HttpStatus.BAD_REQUEST;
            case TypeMismatchException e -> HttpStatus.UNPROCESSABLE_ENTITY;
            case MalformedExpressionException e -> HttpStatus.BAD_REQUEST;
            case SelfReferenceException e -> HttpStatus.CONFLICT;
            case CircularDependencyException e -> HttpStatus.CONFLICT;
            case InvalidDefinitionException e -> HttpStatus.BAD_REQUEST;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorType", ex.getClass().getSimpleName());
        body.put("status", status.value());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }
}
