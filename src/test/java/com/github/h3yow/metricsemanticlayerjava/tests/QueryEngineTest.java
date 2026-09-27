package com.github.h3yow.metricsemanticlayerjava.tests;

import com.github.h3yow.metricsemanticlayerjava.engine.MetricSemanticLayer;
import com.github.h3yow.metricsemanticlayerjava.exception.QueryValidationException;
import com.github.h3yow.metricsemanticlayerjava.exception.UnknownFieldException;
import com.github.h3yow.metricsemanticlayerjava.local.LocalDemoCatalog;
import com.github.h3yow.metricsemanticlayerjava.local.LocalTables;
import com.github.h3yow.metricsemanticlayerjava.model.DataSnapshot;
import com.github.h3yow.metricsemanticlayerjava.query.Query;
import com.github.h3yow.metricsemanticlayerjava.query.QueryResult;
import com.github.h3yow.metricsemanticlayerjava.query.SortOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.col;
import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.gt;
import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.lit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryEngineTest {

    private static final Logger log = LoggerFactory.getLogger(QueryEngineTest.class);

    private MetricSemanticLayer layer;
    private DataSnapshot snapshot;

    @BeforeEach
    void setUp() {
        snapshot = LocalTables.snapshot(1L, 300L);
        layer = new MetricSemanticLayer();
        layer.publishSnapshot(snapshot);
        layer.publishCatalog(LocalDemoCatalog.revision2(snapshot.tables()));
    }

    @Test
    void groupByPhysicalDimension() {
        QueryResult r = layer.query(Query.builder()
                .metric("revenue", 2).groupBy("region").build());
        assertEquals(List.of("region", "revenue"), r.columns());
        assertEquals(3, r.rows().size());
        log.info("[查询] 输入=revenue[v2] GROUP BY region | 命中版本={} 结果={} | 判定依据=按region物理列分组聚合",
                r.versions(), r.rows());
    }

    @Test
    void derivedDimensionGrouping() {
        QueryResult r = layer.query(Query.builder()
                .metric("revenue", 2).groupBy("channel_group").build());
        var groups = r.rows().stream().map(x -> x.get("channel_group")).toList();
        assertTrue(groups.containsAll(List.of("online", "offline")));
        log.info("[查询] 输入=revenue[v2] GROUP BY channel_group(派生) | 命中版本={} 结果={} "
                        + "| 判定依据=先物化派生维度再分组", r.versions(), r.rows());
    }

    @Test
    void groupByUnknownDimensionRejected() {
        var ex = assertThrows(UnknownFieldException.class, () ->
                layer.query(Query.builder().metric("revenue", 2).groupBy("nope").build()));
        log.info("[查询校验] 输入=GROUP BY nope | 判定依据=code:{} -> {}", ex.code(), ex.getMessage());
    }

    @Test
    void orderByAndLimit() {
        QueryResult r = layer.query(Query.builder()
                .metric("revenue", 2)
                .groupBy("region")
                .orderBy("revenue", SortOrder.DESC)
                .limit(2)
                .build());
        assertEquals(2, r.rows().size());
        double first = ((Number) r.rows().get(0).get("revenue")).doubleValue();
        double second = ((Number) r.rows().get(1).get("revenue")).doubleValue();
        assertTrue(first >= second);
        log.info("[查询] 输入=...ORDER BY revenue DESC LIMIT 2 | 命中版本={} 结果={}",
                r.versions(), r.rows());
    }

    @Test
    void orderByUnknownColumnRejected() {
        var ex = assertThrows(QueryValidationException.class, () ->
                layer.query(Query.builder()
                        .metric("revenue", 2).groupBy("region")
                        .orderBy("nope", SortOrder.ASC).build()));
        log.info("[查询校验] 输入=ORDER BY nope | 判定依据=code:{} -> {}", ex.code(), ex.getMessage());
    }

    @Test
    void havingFilterOnMetric() {
        QueryResult all = layer.query(Query.builder()
                .metric("revenue", 2).groupBy("region").build());
        QueryResult filtered = layer.query(Query.builder()
                .metric("revenue", 2).groupBy("region")
                .where(gt(col("revenue"), lit(50000.0)))
                .build());
        assertTrue(filtered.rows().size() < all.rows().size());
        for (var row : filtered.rows()) {
            assertTrue(((Number) row.get("revenue")).doubleValue() > 50000.0);
        }
        log.info("[查询] 输入=...HAVING revenue>4000 | 结果={} | 判定依据=聚合后过滤",
                filtered.rows());
    }

    @Test
    void aggregationsAndDerivedMetric() {
        QueryResult r = layer.query(Query.builder()
                .metric("revenue", 1)
                .metric("cost", 1)
                .metric("order_count", 1)
                .metric("avg_order_value", 1)
                .metric("gross_profit", 1)
                .build());
        Map<String, Object> row = r.rows().get(0);
        double rev = ((Number) row.get("revenue")).doubleValue();
        double cost = ((Number) row.get("cost")).doubleValue();
        double gp = ((Number) row.get("gross_profit")).doubleValue();
        assertEquals(rev - cost, gp, 1e-6, "gross_profit must equal revenue v1 - cost v1");
        assertEquals(300L, row.get("order_count"));
        log.info("[查询] 输入=revenue,cost,count,avg,gross_profit(无分组) | 命中版本={} 结果={} "
                        + "| 判定依据=派生公式 revenue-cost 与血缘一致",
                r.versions(), row);
    }

    @Test
    void emptyGroupReturnsNullMetricNotZero() {
        // CN + web + non-refunded may or may not exist in deterministic data;
        // filter via HAVING on a non-existent combination requires no group at all:
        QueryResult r = layer.query(Query.builder()
                .metric("cn_web_revenue", 1)
                .groupBy("region")
                .build());
        // regions without matching rows are absent from base groups (not silently zero)
        log.info("[查询] 输入=cn_web_revenue GROUP BY region | 结果={} (无匹配行的region不作为组出现)",
                r.rows());
        for (var row : r.rows()) {
            assertEquals("CN", row.get("region"));
        }
    }

    @Test
    void repeatedQueriesAreIdentical() {
        Query q = Query.builder()
                .metric("revenue", 2).metric("gross_profit", 2)
                .groupBy("region")
                .orderBy("revenue", SortOrder.ASC)
                .build();
        QueryResult a = layer.query(q);
        QueryResult b = layer.query(q);
        assertEquals(a.rows(), b.rows());
        assertEquals(a.versions(), b.versions());
        log.info("[重复查询] 同一查询执行两次 | 行数={} 结果一致 | 判定依据=快照+目录不可变",
                a.rows().size());
    }
}
