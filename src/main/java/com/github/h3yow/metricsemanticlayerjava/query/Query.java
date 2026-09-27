package com.github.h3yow.metricsemanticlayerjava.query;

import com.github.h3yow.metricsemanticlayerjava.expr.Expr;

import java.util.List;

/**
 * A metric query.
 *
 * @param metrics        requested metrics with explicit (or default) versions
 * @param groupBy        dimension names (physical or derived) to group on
 * @param where          optional post-aggregation HAVING-style filter over the output names;
 *                       null means no filter
 * @param orderBy        output column names + direction; empty means unspecified order
 * @param limit          max rows; null/&lt;=0 means no limit
 * @param snapshotId     data snapshot to read; null means the latest published snapshot
 */
public record Query(List<MetricRequest> metrics,
                    List<String> groupBy,
                    Expr where,
                    List<OrderTerm> orderBy,
                    Integer limit,
                    Long snapshotId) {

    public Query {
        metrics = List.copyOf(metrics);
        groupBy = List.copyOf(groupBy);
        orderBy = orderBy == null ? List.of() : List.copyOf(orderBy);
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("query must request at least one metric");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public record MetricRequest(String name, Integer version) {
        public MetricRequest {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("metric name must not be blank");
            }
        }

        public static MetricRequest of(String name) {
            return new MetricRequest(name, null);
        }

        public static MetricRequest at(String name, int version) {
            return new MetricRequest(name, version);
        }
    }

    public record OrderTerm(String column, SortOrder direction) {
        public static OrderTerm asc(String column) {
            return new OrderTerm(column, SortOrder.ASC);
        }

        public static OrderTerm desc(String column) {
            return new OrderTerm(column, SortOrder.DESC);
        }
    }

    public static final class Builder {
        private final List<MetricRequest> metrics = new java.util.ArrayList<>();
        private final List<String> groupBy = new java.util.ArrayList<>();
        private Expr where;
        private final List<OrderTerm> orderBy = new java.util.ArrayList<>();
        private Integer limit;
        private Long snapshotId;

        public Builder metric(String name) {
            metrics.add(MetricRequest.of(name));
            return this;
        }

        public Builder metric(String name, int version) {
            metrics.add(MetricRequest.at(name, version));
            return this;
        }

        public Builder groupBy(String dim) {
            groupBy.add(dim);
            return this;
        }

        public Builder where(Expr e) {
            this.where = e;
            return this;
        }

        public Builder orderBy(String col, SortOrder dir) {
            orderBy.add(new OrderTerm(col, dir));
            return this;
        }

        public Builder limit(int n) {
            this.limit = n;
            return this;
        }

        public Builder snapshot(long id) {
            this.snapshotId = id;
            return this;
        }

        public Query build() {
            return new Query(metrics, groupBy, where, orderBy, limit, snapshotId);
        }
    }
}
