package com.github.h3yow.metricsemanticlayerjava.error;

/**
 * 其它结构性非法定义：如同一版本重复注册、派生列与物理列重名、聚合源列缺失等。
 */
public final class InvalidDefinitionException extends DefinitionException {
    public InvalidDefinitionException(String message) {
        super(message);
    }
}
