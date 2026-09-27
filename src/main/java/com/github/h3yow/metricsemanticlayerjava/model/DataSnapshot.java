package com.github.h3yow.metricsemanticlayerjava.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命名的数据快照：持有一组不可变本地表。
 * 查询基于快照求值；同一快照上重复/并发查询结果必须一致。
 */
public final class DataSnapshot {

    private final String id;
    private final Map<String, LocalTable> tables;

    public DataSnapshot(String id, List<LocalTable> tableList) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("快照 id 不能为空");
        }
        this.id = id;
        Map<String, LocalTable> m = new LinkedHashMap<>();
        for (LocalTable t : tableList) {
            m.put(t.name(), t);
        }
        this.tables = Map.copyOf(m);
    }

    public String id() {
        return id;
    }

    public LocalTable table(String name) {
        return tables.get(name);
    }

    public Map<String, LocalTable> tables() {
        return tables;
    }
}
