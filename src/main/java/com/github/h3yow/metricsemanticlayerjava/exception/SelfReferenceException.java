package com.github.h3yow.metricsemanticlayerjava.exception;

/** A metric/dimension definition directly references itself. */
public final class SelfReferenceException extends MetricSemanticException {
    private final String definition;

    public SelfReferenceException(String definition) {
        super("definition '" + definition + "' references itself");
        this.definition = definition;
    }

    public String definition() {
        return definition;
    }
}
