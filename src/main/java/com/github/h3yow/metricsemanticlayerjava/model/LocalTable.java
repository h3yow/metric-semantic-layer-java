package com.github.h3yow.metricsemanticlayerjava.model;

import java.util.List;
import java.util.Map;

/**
 * 本地模拟的分析表：列定义 + 不可变行数据。
 * 一张表代表一个不可变数据快照。
 */
public record LocalTable(String name, List<Column> columns, List<Map<String, Object>> rows) {

    public LocalTable {
        columns = columns == null ? List.of() : List.copyOf(columns);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public Column column(String name) {
        return columns.stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElse(null);
    }
}
