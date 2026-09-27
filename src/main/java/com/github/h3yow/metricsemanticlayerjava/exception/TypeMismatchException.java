package com.github.h3yow.metricsemanticlayerjava.exception;

/** An operand has an incompatible type (aggregation/arithmetic/compare/etc.). */
public final class TypeMismatchException extends MetricSemanticException {
    public TypeMismatchException(String message) {
        super(message);
    }

    public static TypeMismatchException expression(String expr, String expected, String actual) {
        return new TypeMismatchException(
                "type mismatch in " + expr + ": expected " + expected + " but got " + actual);
    }
}
