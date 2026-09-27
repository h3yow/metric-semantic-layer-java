package com.github.h3yow.metricsemanticlayerjava.exception;

/** A definition is structurally malformed (missing table, unknown aggregation, etc.). */
public final class InvalidDefinitionException extends MetricSemanticException {
    public InvalidDefinitionException(String message) {
        super(message);
    }
}
