package com.github.h3yow.metricsemanticlayerjava.model;

/** A named column with a declared type. */
public record FieldDef(String name, DataType type) {
    public FieldDef {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("field name must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("field type must not be null");
        }
    }
}
