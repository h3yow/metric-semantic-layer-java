package com.github.h3yow.metricsemanticlayerjava.exception;

/** A query is invalid against the resolved definitions (e.g. order by unknown output). */
public final class QueryValidationException extends MetricSemanticException {
    public QueryValidationException(String message) {
        super(message);
    }
}
