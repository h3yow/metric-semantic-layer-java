package com.github.h3yow.metricsemanticlayerjava.error;

/**
 * 引用或查询了一个根本不存在的指标名。
 */
public final class MetricNotFoundException extends DefinitionException {
    private final String metric;

    public MetricNotFoundException(String metric) {
        super("指标不存在: '" + metric + "'");
        this.metric = metric;
    }

    public String metric() {
        return metric;
    }
}
