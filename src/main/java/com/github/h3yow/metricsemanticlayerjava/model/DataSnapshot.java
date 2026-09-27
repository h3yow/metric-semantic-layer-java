package com.github.h3yow.metricsemanticlayerjava.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An immutable point-in-time view of all local tables.
 * A query pinned to a snapshot id always sees the same rows.
 */
public record DataSnapshot(long id, String label, Map<String, TableData> tables) {

    public DataSnapshot {
        tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
    }

    public TableData table(String name) {
        TableData t = tables.get(name);
        if (t == null) {
            throw new IllegalArgumentException("unknown table: " + name);
        }
        return t;
    }

    public static Builder builder(long id, String label) {
        return new Builder(id, label);
    }

    public static final class Builder {
        private final long id;
        private final String label;
        private final Map<String, TableData> tables = new LinkedHashMap<>();

        private Builder(long id, String label) {
            this.id = id;
            this.label = label;
        }

        public Builder add(TableData table) {
            tables.put(table.name(), table);
            return this;
        }

        public DataSnapshot build() {
            return new DataSnapshot(id, label, tables);
        }
    }
}
