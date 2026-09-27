package com.github.h3yow.metricsemanticlayerjava.expr;

import com.github.h3yow.metricsemanticlayerjava.model.DataType;

import java.util.Map;

/**
 * A typed, evaluable expression (column reference, literal, comparison, arithmetic...).
 *
 * <p>Every expression is first {@link #bind bound} against a {@link Scope}: unknown
 * fields and type mismatches are rejected there, never silently coerced to null/zero.
 * After binding it can be evaluated against a row view many times.
 */
public interface Expr {

    /** Type-check this expression against the scope; returns the resulting type. */
    DataType bind(Scope scope, String context);

    /** Evaluate against an already-validated row view (physical + derived columns). */
    Object eval(Map<String, Object> row);

    /** Render a stable textual representation, used in logs and failure messages. */
    String describe();

    // fluent sugar: col("a").eq(lit(1)), col("x").and(col("y")), ...
    default Expr eq(Expr o) { return Expressions.eq(this, o); }
    default Expr ne(Expr o) { return Expressions.ne(this, o); }
    default Expr gt(Expr o) { return Expressions.gt(this, o); }
    default Expr ge(Expr o) { return Expressions.ge(this, o); }
    default Expr lt(Expr o) { return Expressions.lt(this, o); }
    default Expr le(Expr o) { return Expressions.le(this, o); }
    default Expr and(Expr o) { return Expressions.and(this, o); }
    default Expr or(Expr o) { return Expressions.or(this, o); }
    default Expr plus(Expr o) { return Expressions.plus(this, o); }
    default Expr minus(Expr o) { return Expressions.minus(this, o); }
    default Expr times(Expr o) { return Expressions.times(this, o); }
    default Expr div(Expr o) { return Expressions.div(this, o); }
    default Expr isNotNull() { return Expressions.isNotNull(this); }
}
