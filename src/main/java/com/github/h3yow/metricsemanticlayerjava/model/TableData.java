package com.github.h3yow.metricsemanticlayerjava.model;

import java.util.List;

/** Local in-memory table: schema plus rows. Immutable. */
public record TableData(String name, List<FieldDef> columns, List<Row> rows) {

    public TableData {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }
}
