package com.github.h3yow.metricsemanticlayerjava.error;

/**
 * 指标/维度定义被拒绝时抛出的根异常。
 * 各类畸形定义用彼此可区分的子类表达，绝不静默按空值或零处理。
 */
public sealed abstract class DefinitionException extends RuntimeException
        permits UnknownFieldException, TypeMismatchException, SelfReferenceException,
                CircularDependencyException, MetricNotFoundException, VersionNotFoundException,
                MalformedExpressionException, InvalidDefinitionException {

    protected DefinitionException(String message) {
        super(message);
    }
}
