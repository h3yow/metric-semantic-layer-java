package com.github.h3yow.metricsemanticlayerjava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One row of a local table: column name -> value.
 * Values are restricted to Long / Double / Boolean / String.
 */
public record Row(Map<String, Object> values) {

    public Row {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static Row of(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("Row.of requires name/value pairs");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return new Row(m);
    }

    public Object get(String column) {
        return values.get(column);
    }
}
