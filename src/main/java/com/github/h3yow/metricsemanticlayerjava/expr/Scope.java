package com.github.h3yow.metricsemanticlayerjava.expr;

import com.github.h3yow.metricsemanticlayerjava.model.DataType;

import java.util.Optional;

/** Typed lookup used when binding (type-checking) an expression. */
@FunctionalInterface
public interface Scope {
    Optional<DataType> typeOf(String name);
}
