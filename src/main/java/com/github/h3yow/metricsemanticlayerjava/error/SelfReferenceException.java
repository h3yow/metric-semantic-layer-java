package com.github.h3yow.metricsemanticlayerjava.error;

/**
 * 指标表达式直接引用了自身（同名同版本）。
 */
public final class SelfReferenceException extends DefinitionException {
    private final String metric;

    public SelfReferenceException(String metric) {
        super("指标 '" + metric + "' 的定义自引用");
        this.metric = metric;
    }

    public String metric() {
        return metric;
    }
}
