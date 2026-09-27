package com.github.h3yow.metricsemanticlayerjava.tests;

import com.github.h3yow.metricsemanticlayerjava.catalog.DemoCatalog;
import com.github.h3yow.metricsemanticlayerjava.engine.DefinitionValidator;
import com.github.h3yow.metricsemanticlayerjava.engine.QueryCache;
import com.github.h3yow.metricsemanticlayerjava.engine.QueryPlanner;
import com.github.h3yow.metricsemanticlayerjava.engine.RowEvaluator;
import com.github.h3yow.metricsemanticlayerjava.engine.SemanticEngine;
import com.github.h3yow.metricsemanticlayerjava.model.Column;
import com.github.h3yow.metricsemanticlayerjava.model.DataSnapshot;
import com.github.h3yow.metricsemanticlayerjava.model.DataType;
import com.github.h3yow.metricsemanticlayerjava.model.LocalTable;
import com.github.h3yow.metricsemanticlayerjava.model.QueryRequest;
import com.github.h3yow.metricsemanticlayerjava.model.QueryResult;
import com.github.h3yow.metricsemanticlayerjava.registry.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 边界三：并发与重复查询。
 * 同一数据快照上并发/重复查询结果一致；缓存避免大数据重复求值；口径或数据变化后不返回过期结果。
 */
class ConcurrencyConsistencyTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyConsistencyTest.class);

    private SemanticEngine newEngine(DataSnapshot snapshot) {
        var registry = new MetricRegistry();
        var planner = new QueryPlanner(registry, new RowEvaluator());
        var engine = new SemanticEngine(registry, new DefinitionValidator(), planner, new QueryCache());
        engine.bindDataSnapshot(snapshot);
        DemoFixtures.load(engine);
        return engine;
    }

    @Test
    void concurrentQueriesOnSameSnapshotReturnIdenticalResults() throws Exception {
        SemanticEngine engine = newEngine(DemoCatalog.buildSnapshot());
        int threads = 16;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Map<Integer, List<Map<String, Object>>> results = new ConcurrentHashMap<>();
        AtomicInteger errors = new AtomicInteger();

        QueryRequest req = QueryRequest.builder("aov")
                .dimensions(List.of("region", "market"))
                .filters(List.of("is_refund = false"))
                .orderBy("aov").build();

        for (int t = 0; t < threads; t++) {
            int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    List<List<Map<String, Object>>> local = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        QueryResult r = engine.query(req);
                        local.add(r.rows());
                    }
                    results.put(tid, local.get(0));
                    for (List<Map<String, Object>> row : local) {
                        assertEquals(results.get(tid), row);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                    log.error("并发查询异常", e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(0, errors.get());
        // 所有线程的结果行集合完全一致
        List<Map<String, Object>> expected = results.get(0);
        for (List<Map<String, Object>> r : results.values()) {
            assertEquals(expected, r);
        }
        log.info("[并发一致性] {} 线程 x {} 次 aov 分组查询结果完全一致；缓存命中={}",
                threads, perThread, engine.cache().hitCount());
    }

    @Test
    void largeSnapshotIsNotRecomputedForRepeatedQueries() {
        DataSnapshot big = buildLargeSnapshot(200_000);
        SemanticEngine engine = newEngine(big);

        QueryRequest req = QueryRequest.builder("gmv").version(1)
                .dimensions(List.of("region")).build();

        long t0 = System.nanoTime();
        QueryResult first = engine.query(req);
        long coldMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            QueryResult again = engine.query(req);
            assertEquals(first.rows(), again.rows());
        }
        long warmMs = (System.nanoTime() - t1) / 1_000_000;

        log.info("[大数据缓存] 行数=200000 冷查询={}ms，100 次重复热查询合计={}ms，缓存命中={}",
                coldMs, warmMs, engine.cache().hitCount());
        assertEquals(2, first.rows().size());
        assertTrue(engine.cache().hitCount() >= 100, "重复查询必须命中缓存");
        // 热路径应当远快于冷路径（纯内存查表）；给宽松阈值避免环境抖动
        assertTrue(warmMs < Math.max(500, coldMs),
                "重复查询不应触发重复大数据求值");
    }

    @Test
    void cacheNeverReturnsStaleResultsAfterDefinitionOrDataChange() {
        SemanticEngine engine = newEngine(DemoCatalog.buildSnapshot());
        QueryRequest defaultGmv = QueryRequest.builder("gmv").build();

        // 初始默认 v1 = 1800
        assertEquals(1800.0, (Double) engine.query(defaultGmv).rows().get(0).get("gmv"), 1e-9);

        // 口径切换后默认查询必须反映新值（缓存键含代际，不得返回旧的 v1）
        engine.setDefaultVersion("gmv", 2);
        QueryResult afterSwitch = engine.query(defaultGmv);
        assertEquals(1300.0, (Double) afterSwitch.rows().get(0).get("gmv"), 1e-9);
        assertEquals(2, afterSwitch.metricVersion());
        log.info("[不过期-口径] 默认版本切换后 gmv={} 命中v{}",
                afterSwitch.rows().get(0).get("gmv"), afterSwitch.metricVersion());

        // 注册新维度再按其查询，也不受旧缓存影响
        // 数据快照变化（换一个更小的快照，id 不同）
        DataSnapshot other = new DataSnapshot("snap-other",
                List.of(new LocalTable("orders",
                        DemoCatalog.buildSnapshot().table("orders").columns(),
                        DemoCatalog.buildSnapshot().table("orders").rows().subList(0, 2))));
        engine.bindDataSnapshot(other);
        QueryResult onOther = engine.query(QueryRequest.builder("gmv").version(1).build());
        assertEquals(250.0, (Double) onOther.rows().get(0).get("gmv"), 1e-9);
        assertEquals("snap-other", onOther.snapshotId());
        log.info("[不过期-数据] 切换快照后 gmv v1={} snapshot={}",
                onOther.rows().get(0).get("gmv"), onOther.snapshotId());
    }

    @Test
    void noHalfPublishedDefinitionVisibleDuringConcurrentRegistration() throws Exception {
        // 动态注册一批新版本的同时不断查询：进行中的查询只能看到完整的某一代注册表
        SemanticEngine engine = newEngine(DemoCatalog.buildSnapshot());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        List<Double> observed = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < 4; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 300; i++) {
                        QueryResult r = engine.query(QueryRequest.builder("gmv").build());
                        double v = (Double) r.rows().get(0).get("gmv");
                        // 只可能是已知口径值，绝不出现半成品
                        assertTrue(v == 1800.0 || v == 1300.0, "异常值 " + v);
                        observed.add(v);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                }
            });
        }
        pool.submit(() -> {
            try {
                start.await();
                for (int v = 3; v < 50; v++) {
                    engine.registerMetric(new com.github.h3yow.metricsemanticlayerjava.model.MetricDefinition(
                            "gmv", v, "orders", "amount",
                            com.github.h3yow.metricsemanticlayerjava.model.AggType.SUM,
                            null, null, "临时版本 " + v));
                }
            } catch (Throwable e) {
                errors.incrementAndGet();
            }
        });
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        log.info("[发布原子性] 并发注册期间查询无异常，观测值种类={}（只含已发布完整口径）",
                new java.util.TreeSet<>(observed));
    }

    private DataSnapshot buildLargeSnapshot(int n) {
        List<Column> columns = List.of(
                new Column("order_id", DataType.LONG),
                new Column("region", DataType.STRING),
                new Column("channel", DataType.STRING),
                new Column("price", DataType.DOUBLE),
                new Column("qty", DataType.LONG),
                new Column("amount", DataType.DOUBLE),
                new Column("is_refund", DataType.BOOLEAN),
                new Column("biz_date", DataType.LONG)
        );
        List<Map<String, Object>> rows = new ArrayList<>(n);
        for (long i = 1; i <= n; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("order_id", i);
            r.put("region", i % 2 == 0 ? "CN" : "US");
            r.put("channel", i % 3 == 0 ? "app" : "web");
            r.put("price", (double) (i % 100));
            r.put("qty", i % 5);
            r.put("amount", (double) (i % 1000));
            r.put("is_refund", i % 17 == 0);
            r.put("biz_date", 20240101L + (i % 300));
            rows.add(Map.copyOf(r));
        }
        return new DataSnapshot("snap-large-" + n, List.of(new LocalTable("orders", columns, rows)));
    }
}
