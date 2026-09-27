package com.github.h3yow.metricsemanticlayerjava.model;

import java.util.List;
import java.util.Map;

/**
 * 一次指标查询的结果。
 *
 * @param metricName      命中的指标名
 * @param metricVersion   命中的版本号
 * @param snapshotId      数据快照 id
 * @param dimensions      实际使用的维度列（按顺序）
 * @param rows            分组结果行：维度值 + 指标值
 * @param resolvedChain   本次求值追溯到的指标依赖链（含版本）
 * @param rationale       判定依据说明（版本选择、过滤、缓存命中等）
 * @param fromCache       是否来自缓存
 * @param elapsedMillis   耗时
 */
public record QueryResult(
        String metricName,
        int metricVersion,
        String snapshotId,
        List<String> dimensions,
        List<Map<String, Object>> rows,
        List<String> resolvedChain,
        String rationale,
        boolean fromCache,
        long elapsedMillis
) {
    public QueryResult {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        rows = rows == null ? List.of() : List.copyOf(rows);
        resolvedChain = resolvedChain == null ? List.of() : List.copyOf(resolvedChain);
    }
}
