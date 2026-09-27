package com.github.h3yow.metricsemanticlayerjava.tests;

import com.github.h3yow.metricsemanticlayerjava.definition.Aggregation;
import com.github.h3yow.metricsemanticlayerjava.definition.Catalog;
import com.github.h3yow.metricsemanticlayerjava.definition.MetricDef;
import com.github.h3yow.metricsemanticlayerjava.engine.MetricSemanticLayer;
import com.github.h3yow.metricsemanticlayerjava.local.LocalDemoCatalog;
import com.github.h3yow.metricsemanticlayerjava.local.LocalTables;
import com.github.h3yow.metricsemanticlayerjava.model.DataSnapshot;
import com.github.h3yow.metricsemanticlayerjava.query.Query;
import com.github.h3yow.metricsemanticlayerjava.query.QueryResult;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.col;
import static com.github.h3yow.metricsemanticlayerjava.local.LocalTables.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyTest.class);

    @Test
    void concurrentQueriesOnSameSnapshotReturnIdenticalResults() throws Exception {
        DataSnapshot snapshot = LocalTables.snapshot(1L, 5_000L);
        MetricSemanticLayer layer = new MetricSemanticLayer();
        layer.publishSnapshot(snapshot);
        layer.publishCatalog(LocalDemoCatalog.revision2(snapshot.tables()));

        int threads = 16;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Future<List<Map<String, Object>>>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                List<Map<String, Object>> mine = null;
                for (int i = 0; i < perThread; i++) {
                    Query q = Query.builder()
                            .metric("revenue", 2)
                            .metric("gross_profit", 2)
                            .groupBy("region")
                            .groupBy("channel_group")
                            .build();
                    QueryResult r = layer.query(q);
                    List<Map<String, Object>> snapshot1 = r.rows();
                    if (mine == null) {
                        mine = snapshot1;
                    } else if (!mine.equals(snapshot1)) {
                        throw new AssertionError("concurrent query returned diverging rows");
                    }
                    // every answer must identify the same generation
                    if (r.snapshotId() != 1L || r.catalogRevision() != 2L) {
                        throw new AssertionError("generation drift: "
                                + r.snapshotId() + "/" + r.catalogRevision());
                    }
                }
                return mine;
            }));
        }
        start.countDown();

        List<Map<String, Object>> reference = futures.get(0).get();
        for (var f : futures) {
            assertEquals(reference, f.get());
        }
        pool.shutdown();
        assertTrue(errors.isEmpty(), errors.toString());
        log.info("[并发一致性] 输入={}线程x{}次 查询 revenue/gross_profit GROUP BY region,channel_group | "
                        + "全部{}份结果逐行一致, 命中=snapshot1/revision2 | 判定依据=不可变代次+记忆化聚合",
                threads, perThread, threads * perThread);
    }

    @Test
    void queriesDuringCatalogRepublishNeverSeeHalfDefinition() throws Exception {
        DataSnapshot snapshot = LocalTables.snapshot(1L, 2_000L);
        MetricSemanticLayer layer = new MetricSemanticLayer();
        layer.publishSnapshot(snapshot);
        layer.publishCatalog(LocalDemoCatalog.revision1(snapshot.tables()));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch stop = new CountDownLatch(1);
        AtomicInteger v1Hits = new AtomicInteger();
        AtomicInteger v2Hits = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();

        List<Future<?>> readers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            readers.add(pool.submit(() -> {
                try {
                    while (stop.getCount() > 0) {
                        QueryResult r = layer.query(Query.builder()
                                .metric("revenue").metric("gross_profit").build());
                        int revenueVersion = r.versions().get("revenue");
                        int gpVersion = r.versions().get("gross_profit");
                        // valid combinations only: (1,1) on revision1, (2,2) on revision2
                        boolean coherent = (revenueVersion == 1 && gpVersion == 1)
                                || (revenueVersion == 2 && gpVersion == 2);
                        if (!coherent) {
                            throw new AssertionError(
                                    "torn read: revenue v" + revenueVersion + " with gross_profit v" + gpVersion);
                        }
                        if (revenueVersion == 1) {
                            v1Hits.incrementAndGet();
                        } else {
                            v2Hits.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    if (!(t instanceof InterruptedException)) {
                        errors.add(t);
                    }
                }
            }));
        }

        Future<?> publisher = pool.submit(() -> {
            for (int i = 0; i < 50; i++) {
                layer.publishCatalog(
                        (i % 2 == 0 ? LocalDemoCatalog.revision1(snapshot.tables())
                                : LocalDemoCatalog.revision2(snapshot.tables())));
                Thread.yield();
            }
            return null;
        });
        publisher.get();
        Thread.sleep(100);
        stop.countDown();
        for (Future<?> f : readers) {
            f.get();
        }
        pool.shutdown();

        assertTrue(errors.isEmpty(), "torn reads: " + errors);
        log.info("[发布原子性] 50次目录切换期间持续查询 | v1命中={}, v2命中={}, 无撕裂读 | "
                        + "判定依据=AtomicReference整体替换Generation, 查询开始即钉住代次",
                v1Hits.get(), v2Hits.get());
    }

    @Test
    void memoizationKeepsRepeatedQueriesFastOnLargeSnapshot() {
        DataSnapshot snapshot = LocalTables.snapshot(1L, 200_000L);
        MetricSemanticLayer layer = new MetricSemanticLayer();
        layer.publishSnapshot(snapshot);
        Catalog catalog = Catalog.builder(3).withTables(snapshot.tables())
                .addMetric(new MetricDef.BaseMetricDef("revenue", 1, "sum amount",
                        ORDERS, Aggregation.SUM, col("amount"), null))
                .build();
        layer.publishCatalog(catalog);

        Query q = Query.builder().metric("revenue", 1).groupBy("region").build();

        long first = Duration.ofNanos(slowRun(layer, q)).toMillis();
        long second = Duration.ofNanos(slowRun(layer, q)).toMillis();
        long third = Duration.ofNanos(slowRun(layer, q)).toMillis();
        log.info("[性能] 输入=200000行 revenue GROUP BY region | 首次={}ms 命中缓存后={}ms/{}ms | "
                        + "判定依据=聚合按代次记忆化, 且随新代次失效不会返回过期值",
                first, second, third);
        assertTrue(second <= first, "cached repeat must not rescan the whole table");
        assertTrue(third <= first);
    }

    private long slowRun(MetricSemanticLayer layer, Query q) {
        long t0 = System.nanoTime();
        QueryResult r = layer.query(q);
        long elapsed = System.nanoTime() - t0;
        assertEquals(3, r.rows().size());
        return elapsed;
    }
}
