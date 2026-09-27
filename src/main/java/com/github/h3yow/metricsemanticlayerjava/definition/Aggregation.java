package com.github.h3yow.metricsemanticlayerjava.definition;

import com.github.h3yow.metricsemanticlayerjava.exception.InvalidDefinitionException;
import com.github.h3yow.metricsemanticlayerjava.exception.TypeMismatchException;
import com.github.h3yow.metricsemanticlayerjava.expr.Expressions;
import com.github.h3yow.metricsemanticlayerjava.model.DataType;

/** Aggregations supported by base metrics. */
public enum Aggregation {
    SUM,
    COUNT,
    COUNT_DISTINCT,
    AVG,
    MIN,
    MAX;

    /** Result type given the aggregated expression's type. */
    public DataType resultType(DataType input, boolean countRows) {
        return switch (this) {
            case SUM -> {
                if (!Expressions.isNumeric(input)) {
                    throw TypeMismatchException.expression(
                            "SUM", "numeric input", input.toString());
                }
                yield input;
            }
            case AVG -> {
                if (!Expressions.isNumeric(input)) {
                    throw TypeMismatchException.expression(
                            "AVG", "numeric input", input.toString());
                }
                yield DataType.DOUBLE;
            }
            case MIN, MAX -> input;
            case COUNT, COUNT_DISTINCT -> {
                if (!countRows && input == null) {
                    throw new InvalidDefinitionException(name() + " requires an input expression");
                }
                yield DataType.LONG;
            }
        };
    }
}
