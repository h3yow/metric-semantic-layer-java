package com.github.h3yow.metricsemanticlayerjava.exception;

/**
 * Base class for all semantic-layer failures. Every subclass has a distinct
 * {@link #code()} so malformed definitions are rejected with distinguishable
 * reasons instead of being silently treated as null/zero.
 */
public sealed class MetricSemanticException extends RuntimeException
        permits UnknownFieldException, UnknownMetricException, UnknownVersionException,
                TypeMismatchException, SelfReferenceException, CircularDependencyException,
                InvalidDefinitionException, QueryValidationException {

    protected MetricSemanticException(String message) {
        super(message);
    }

    public String code() {
        return getClass().getSimpleName();
    }
}
