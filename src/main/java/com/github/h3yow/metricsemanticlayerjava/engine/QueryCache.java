package com.github.h3yow.metricsemanticlayerjava.engine;

import com.github.h3yow.metricsemanticlayerjava.model.QueryRequest;
import com.github.h3yow.metricsemanticlayerjava.model.QueryResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 查询结果缓存。
 *
 * <p>缓存键包含数据快照 id、注册表代际、指标名/显式版本、分组维度、全部过滤、having、
 * 排序、limit。口径一变注册表代际就变；数据一变快照 id 就变——键随之变化，
 * 因此永远不会返回过期结果。重复查询直接命中，不重复求值大结果集。</p>
 */
public final class QueryCache {

    /** 完整缓存键：值对象，覆盖全部查询输入。 */
    record CacheKey(String snapshotId, long generation, String metric, Integer version,
                    List<String> dimensions, List<String> filters, String having,
                    String orderBy, String sortOrder, int limit) {

        CacheKey {
            // 防御性拷贝，保证键不可变
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
            filters = filters == null ? List.of() : List.copyOf(filters);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CacheKey other)) {
                return false;
            }
            return limit == other.limit
                    && generation == other.generation
                    && Objects.equals(snapshotId, other.snapshotId)
                    && Objects.equals(metric, other.metric)
                    && Objects.equals(version, other.version)
                    && Objects.equals(dimensions, other.dimensions)
                    && Objects.equals(filters, other.filters)
                    && Objects.equals(having, other.having)
                    && Objects.equals(orderBy, other.orderBy)
                    && Objects.equals(sortOrder, other.sortOrder);
        }

        @Override
        public int hashCode() {
            return Objects.hash(snapshotId, generation, metric, version, dimensions, filters,
                    having, orderBy, sortOrder, limit);
        }
    }

    private final ConcurrentHashMap<CacheKey, QueryResult> store = new ConcurrentHashMap<>();

    /** 命中统计（测试可观测）。 */
    private final java.util.concurrent.atomic.AtomicLong hits =
            new java.util.concurrent.atomic.AtomicLong();

    public static CacheKey keyOf(QueryRequest request, String snapshotId, long generation) {
        return new CacheKey(
                snapshotId,
                generation,
                request.metricName(),
                request.metricVersion(),
                request.dimensions(),
                request.filters(),
                request.having(),
                request.orderBy(),
                request.sortOrder() == null ? null : request.sortOrder().name(),
                request.limit());
    }

    public QueryResult get(CacheKey key) {
        QueryResult r = store.get(key);
        if (r != null) {
            hits.incrementAndGet();
        }
        return r;
    }

    public void put(CacheKey key, QueryResult result) {
        store.put(key, result);
    }

    public long hitCount() {
        return hits.get();
    }

    public int size() {
        return store.size();
    }

    /** 测试辅助：清空。 */
    public void clear() {
        store.clear();
        hits.set(0);
    }
}
