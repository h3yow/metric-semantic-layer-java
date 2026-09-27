package com.github.h3yow.metricsemanticlayerjava.definition;

import com.github.h3yow.metricsemanticlayerjava.expr.Expr;

/**
 * A named derived column computed from physical columns and earlier derived
 * dimensions of the same table.
 *
 * @param table physical table this dimension is attached to
 * @param name  output column name (must not collide with a physical column)
 * @param expr  value expression
 */
public record DerivedDimensionDef(String table, String name, Expr expr) {
}
