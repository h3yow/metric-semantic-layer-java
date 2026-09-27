package com.github.h3yow.metricsemanticlayerjava.exception;

import java.util.List;

/** Metric/dimension definitions form a dependency cycle. */
public final class CircularDependencyException extends MetricSemanticException {
    private final List<String> cycle;

    public CircularDependencyException(List<String> cycle) {
        super("circular dependency detected: " + String.join(" -> ", cycle));
        this.cycle = List.copyOf(cycle);
    }

    public List<String> cycle() {
        return cycle;
    }
}
