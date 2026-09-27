package com.github.h3yow.metricsemanticlayerjava.exception;

/** A query or definition references a metric name that is not in the catalog. */
public final class UnknownMetricException extends MetricSemanticException {
    private final String metric;

    public UnknownMetricException(String metric) {
        super("unknown metric '" + metric + "'");
        this.metric = metric;
    }

    public String metric() {
        return metric;
    }
}
