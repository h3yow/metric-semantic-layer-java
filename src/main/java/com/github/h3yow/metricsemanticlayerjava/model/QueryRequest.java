package com.github.h3yow.metricsemanticlayerjava.model;

import java.util.List;

/**
 * 指标查询请求。
 *
 * @param metricName    指标名
 * @param metricVersion 指标版本；null 表示取当前默认版本
 * @param table         来源表（必须与定义一致，校验用）
 * @param dimensions    分组维度（源表列或派生维度）
 * @param filters       聚合前过滤表达式（在指标自带过滤之外再叠加）
 * @param having        聚合后过滤表达式，引用指标别名与维度
 * @param orderBy       排序维度或指标
 * @param sortOrder     排序方向
 * @param limit         返回行数上限，&lt;=0 表示不限制
 * @param snapshotId    数据快照 id（用于同一快照并发一致性校验）
 */
public record QueryRequest(
        String metricName,
        Integer metricVersion,
        String table,
        List<String> dimensions,
        List<String> filters,
        String having,
        String orderBy,
        SortOrder sortOrder,
        int limit,
        String snapshotId
) {
    public QueryRequest {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    public static Builder builder(String metricName) {
        return new Builder(metricName);
    }

    /** 链式构造器。 */
    public static final class Builder {
        private final String metricName;
        private Integer metricVersion;
        private String table;
        private List<String> dimensions = List.of();
        private List<String> filters = List.of();
        private String having;
        private String orderBy;
        private SortOrder sortOrder = SortOrder.ASC;
        private int limit;
        private String snapshotId;

        private Builder(String metricName) {
            this.metricName = metricName;
        }

        public Builder version(Integer v) { this.metricVersion = v; return this; }
        public Builder table(String t) { this.table = t; return this; }
        public Builder dimensions(List<String> d) { this.dimensions = d; return this; }
        public Builder filters(List<String> f) { this.filters = f; return this; }
        public Builder having(String h) { this.having = h; return this; }
        public Builder orderBy(String o) { this.orderBy = o; return this; }
        public Builder orderBy(String o, SortOrder s) { this.orderBy = o; this.sortOrder = s; return this; }
        public Builder sortOrder(SortOrder s) { this.sortOrder = s; return this; }
        public Builder limit(int l) { this.limit = l; return this; }
        public Builder snapshotId(String s) { this.snapshotId = s; return this; }

        public QueryRequest build() {
            return new QueryRequest(metricName, metricVersion, table, dimensions, filters,
                    having, orderBy, sortOrder, limit, snapshotId);
        }
    }
}
