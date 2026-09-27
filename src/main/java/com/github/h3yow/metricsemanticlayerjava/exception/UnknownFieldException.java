package com.github.h3yow.metricsemanticlayerjava.exception;

/** A definition/query references a column or derived field that does not exist. */
public final class UnknownFieldException extends MetricSemanticException {
    private final String field;

    public UnknownFieldException(String field, String context) {
        super("unknown field '" + field + "' referenced from " + context);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
