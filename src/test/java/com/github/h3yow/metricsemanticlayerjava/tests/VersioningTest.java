package com.github.h3yow.metricsemanticlayerjava.tests;

import com.github.h3yow.metricsemanticlayerjava.engine.MetricSemanticLayer;
import com.github.h3yow.metricsemanticlayerjava.local.LocalDemoCatalog;
import com.github.h3yow.metricsemanticlayerjava.local.LocalTables;
import com.github.h3yow.metricsemanticlayerjava.model.DataSnapshot;
import com.github.h3yow.metricsemanticlayerjava.query.LineageNode;
import com.github.h3yow.metricsemanticlayerjava.query.Query;
import com.github.h3yow.metricsemanticlayerjava.query.QueryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Version semantics:
 * 1) old-version queries reproduce the old value after the default moves on;
 * 2) derived metrics compiled against pinned dependencies never mix versions;
 * 3) lineage exposes the exact versions used.
 */
class VersioningTest {

    private static final Logger log = LoggerFactory.getLogger(VersioningTest.class);

    private MetricSemanticLayer layer;
    private DataSnapshot snapshot;

    @BeforeEach
    void setUp() {
        snapshot = LocalTables.snapshot(1L, 200L);
        layer = new MetricSemanticLayer();
        layer.publishSnapshot(snapshot);
    }

    private Object revenue(QueryResult r) {
        return r.rows().get(0).get("revenue");
    }

    private Object grossProfit(QueryResult r) {
        return r.rows().get(0).get("gross_profit");
    }

    @Test
    void oldVersionReproducesAfterDefaultSwitch() {
        // revision 1: default revenue = v1
        layer.publishCatalog(LocalDemoCatalog.revision1(snapshot.tables()));
        QueryResult beforeDefault = layer.query(Query.builder().metric("revenue").build());
        Object v1Value = revenue(beforeDefault);
        assertEquals(1, beforeDefault.versions().get("revenue"));

        // revision 2: default revenue -> v2
        layer.publishCatalog(LocalDemoCatalog.revision2(snapshot.tables()));
        QueryResult afterDefault = layer.query(Query.builder().metric("revenue").build());
        Object v2Value = revenue(afterDefault);
        assertEquals(2, afterDefault.versions().get("revenue"));
        assertNotEquals(v1Value, v2Value, "v1 includes refunds, v2 excludes them");

        // pinned v1 query must still reproduce the historical v1 number
        QueryResult pinnedV1 = layer.query(Query.builder().metric("revenue", 1).build());
        assertEquals(v1Value, revenue(pinnedV1));
        assertEquals(1, pinnedV1.versions().get("revenue"));

        log.info("[版本复现] 输入=revenue | 旧默认(v1)={} 命中版本={} ; 新默认(v2)={} 命中版本={} ; "
                        + "显式钉v1复现={} | 判定依据=数值按快照预聚合+版本不可变",
                v1Value, 1, v2Value, 2, revenue(pinnedV1));
    }

    @Test
    void derivedMetricNeverMixesVersions() {
        layer.publishCatalog(LocalDemoCatalog.revision2(snapshot.tables()));

        QueryResult gp1 = layer.query(Query.builder().metric("gross_profit", 1).build());
        QueryResult gp2 = layer.query(Query.builder().metric("gross_profit", 2).build());
        assertNotEquals(grossProfit(gp1), grossProfit(gp2));

        LineageNode l1 = layer.lineage("gross_profit", 1);
        LineageNode l2 = layer.lineage("gross_profit", 2);

        int dep1 = l1.dependencies().stream()
                .filter(n -> n.metric().equals("revenue")).findFirst().orElseThrow().version();
        int dep2 = l2.dependencies().stream()
                .filter(n -> n.metric().equals("revenue")).findFirst().orElseThrow().version();
        assertEquals(1, dep1);
        assertEquals(2, dep2);

        // default gross_profit now resolves to v2, still pinned to revenue v2
        QueryResult gpDefault = layer.query(Query.builder().metric("gross_profit").build());
        assertEquals(2, gpDefault.versions().get("gross_profit"));
        assertEquals(grossProfit(gp2), grossProfit(gpDefault));

        log.info("[依赖追溯] gross_profit v1={} lineage->revenue[v{}] ; gross_profit v2={} "
                        + "lineage->revenue[v{}] | 判定依据=依赖引用必须显式带版本",
                grossProfit(gp1), dep1, grossProfit(gp2), dep2);
    }

    @Test
    void unknownVersionIsRejected() {
        layer.publishCatalog(LocalDemoCatalog.revision2(snapshot.tables()));
        var ex = assertThrows(com.github.h3yow.metricsemanticlayerjava.exception.UnknownVersionException.class,
                () -> layer.query(Query.builder().metric("revenue", 99).build()));
        log.info("[版本校验] 输入=revenue[v99] | 判定依据=code:{} -> {}", ex.code(), ex.getMessage());
    }

    @Test
    void staleSnapshotPinnedIsRejected() {
        layer.publishCatalog(LocalDemoCatalog.revision2(snapshot.tables()));
        // publish a newer snapshot
        layer.publishSnapshot(LocalTables.snapshot(2L, 200L));
        var ex = assertThrows(com.github.h3yow.metricsemanticlayerjava.exception.InvalidDefinitionException.class,
                () -> layer.query(Query.builder().metric("revenue", 1).snapshot(1L).build()));
        log.info("[快照新鲜度] 输入=snapshotId=1(current=2) | 判定依据=code:{} -> {}",
                ex.code(), ex.getMessage());
    }

    @Test
    void groupedHistoricalQueryAlsoReproduces() {
        layer.publishCatalog(LocalDemoCatalog.revision1(snapshot.tables()));
        var before = layer.query(Query.builder().metric("revenue").groupBy("region").build());
        layer.publishCatalog(LocalDemoCatalog.revision2(snapshot.tables()));
        var pinned = layer.query(Query.builder()
                .metric("revenue", 1).groupBy("region").build());
        assertEquals(before.rows(), pinned.rows());
        log.info("[版本复现] 输入=revenue[v1] GROUP BY region | 命中版本={} 行数={} | 判定依据=分组结果逐行一致",
                pinned.versions().get("revenue"), pinned.rows().size());
    }
}
