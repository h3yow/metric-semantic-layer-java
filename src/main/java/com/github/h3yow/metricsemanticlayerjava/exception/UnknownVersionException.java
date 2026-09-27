package com.github.h3yow.metricsemanticlayerjava.exception;

/** A metric is requested at, or references, a version that does not exist. */
public final class UnknownVersionException extends MetricSemanticException {
    private final String metric;
    private final int version;

    public UnknownVersionException(String metric, int version) {
        super("metric '" + metric + "' has no version v" + version);
        this.metric = metric;
        this.version = version;
    }

    public String metric() {
        return metric;
    }

    public int version() {
        return version;
    }
}
