package com.github.h3yow.metricsemanticlayerjava.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 指标的一个不可变版本定义。
 *
 * <p>依赖其它指标时，表达式中以 {@code 指标名@版本号} 形式引用，
 * 版本号被固定，避免跨版本口径混用。</p>
 *
 * @param name             指标名（同一指标可有多个版本）
 * @param version          版本号（从 1 开始）
 * @param table            来源本地表
 * @param sourceColumn     聚合的源列（COUNT 可为空）
 * @param agg              聚合方式
 * @param filterExpression 聚合前行过滤表达式（可空），如 "amount > 100 and region = 'CN'"
 * @param expression       派生表达式（可空）：可引用源表列与其它指标，如 "price * qty"、"gmv@2 / orders@1"
 * @param description      口径说明
 */
public record MetricDefinition(
        String name,
        int version,
        String table,
        String sourceColumn,
        AggType agg,
        String filterExpression,
        String expression,
        String description
) {
    public MetricDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("指标名不能为空");
        }
        if (version < 1) {
            throw new IllegalArgumentException("版本号必须 >= 1");
        }
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("来源表不能为空");
        }
        if (agg == null) {
            throw new IllegalArgumentException("聚合方式不能为 null");
        }
    }

    /** 定义里出现的所有字段名（源列 + 表达式引用的列）。 */
    public Map<String, String> toDisplayMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("version", String.valueOf(version));
        m.put("table", table);
        m.put("sourceColumn", sourceColumn == null ? "" : sourceColumn);
        m.put("agg", agg.name());
        m.put("filterExpression", filterExpression == null ? "" : filterExpression);
        m.put("expression", expression == null ? "" : expression);
        m.put("description", description == null ? "" : description);
        return m;
    }
}
