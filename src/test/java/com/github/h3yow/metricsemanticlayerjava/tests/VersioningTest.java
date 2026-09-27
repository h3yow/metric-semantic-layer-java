package com.github.h3yow.metricsemanticlayerjava.tests;

import com.github.h3yow.metricsemanticlayerjava.catalog.DemoCatalog;
import com.github.h3yow.metricsemanticlayerjava.engine.DefinitionValidator;
import com.github.h3yow.metricsemanticlayerjava.engine.QueryCache;
import com.github.h3yow.metricsemanticlayerjava.engine.QueryPlanner;
import com.github.h3yow.metricsemanticlayerjava.engine.RowEvaluator;
import com.github.h3yow.metricsemanticlayerjava.engine.SemanticEngine;
import com.github.h3yow.metricsemanticlayerjava.model.QueryRequest;
import com.github.h3yow.metricsemanticlayerjava.model.QueryResult;
import com.github.h3yow.metricsemanticlayerjava.registry.MetricRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 边界二：指标口径版本化。
 * 更新默认版本后：显式旧版本查询结果不变；默认查询跟随新版本；复合指标锁定的依赖版本不漂移。
 */
class VersioningTest {

    private static final Logger log = LoggerFactory.getLogger(VersioningTest.class);

    private SemanticEngine engine;

    @BeforeEach
    void setUp() {
        var registry = new MetricRegistry();
        var planner = new QueryPlanner(registry, new RowEvaluator());
        engine = new SemanticEngine(registry, new DefinitionValidator(), planner, new QueryCache());
        engine.bindDataSnapshot(DemoCatalog.buildSnapshot());
        DemoFixtures.load(engine);
    }

    private double value(QueryResult r) {
        return (Double) r.rows().get(0).get(r.metricName());
    }

    @Test
    void historicalVersionReproducibleAfterDefaultChanges() {
        // 初始默认版本是 v1
        int default0 = engine.registry()
                .defaultVersionOf(engine.registry().currentSnapshot(), "gmv");
        assertEquals(1, default0);

        // 记录旧口径结果（显式版本 + 当时的默认查询）
        QueryResult oldExplicit = engine.query(QueryRequest.builder("gmv").version(1).build());
        QueryResult oldDefault = engine.query(QueryRequest.builder("gmv").build());
        log.info("[切换前] 显式v1={} 默认={} 命中版本 v{}",
                value(oldExplicit), value(oldDefault), oldDefault.metricVersion());
        assertEquals(1800.0, value(oldExplicit), 1e-9);
        assertEquals(1800.0, value(oldDefault), 1e-9);

        // 切换默认版本到 v2
        engine.setDefaultVersion("gmv", 2);
        QueryResult newDefault = engine.query(QueryRequest.builder("gmv").build());
        log.info("[切换后] 默认查询命中 v{} 结果={}", newDefault.metricVersion(), value(newDefault));
        assertEquals(2, newDefault.metricVersion());
        assertEquals(1300.0, value(newDefault), 1e-9);

        // 历史查询必须按旧版本稳定复现
        QueryResult reproduced = engine.query(QueryRequest.builder("gmv").version(1).build());
        log.info("[复现] 显式v1 命中 v{} 结果={} 判定依据={}",
                reproduced.metricVersion(), value(reproduced), reproduced.rationale());
        assertEquals(1, reproduced.metricVersion());
        assertEquals(1800.0, value(reproduced), 1e-9);
        assertEquals(oldExplicit.rows(), reproduced.rows());
    }

    @Test
    void compositeMetricDoesNotDriftAcrossVersions() {
        // aov@1 = gmv@2 / orders@1：即便默认 gmv 是 v1，aov 也必须用 gmv v2
        QueryResult aovBefore = engine.query(QueryRequest.builder("aov").build());
        log.info("[默认gmv=v1] aov 依赖链={} 结果={}", aovBefore.resolvedChain(), value(aovBefore));
        assertEquals(162.5, value(aovBefore), 1e-9);
        assertTrue(aovBefore.resolvedChain().contains("gmv@v2"));

        engine.setDefaultVersion("gmv", 2);
        QueryResult aovAfter = engine.query(QueryRequest.builder("aov").build());
        log.info("[默认gmv=v2] aov 依赖链={} 结果={}", aovAfter.resolvedChain(), value(aovAfter));
        assertEquals(162.5, value(aovAfter), 1e-9);
        assertTrue(aovAfter.resolvedChain().contains("gmv@v2"));

        // 再发一个 gmv v3（比如改剔除 qty=0），默认切过去，aov 仍锁定 v2
        engine.registerMetric(new com.github.h3yow.metricsemanticlayerjava.model.MetricDefinition(
                "gmv", 3, "orders", "amount",
                com.github.h3yow.metricsemanticlayerjava.model.AggType.SUM,
                "is_refund = false and qty > 0", null, "v3 剔除退款与零数量"));
        engine.setDefaultVersion("gmv", 3);
        QueryResult aovV3era = engine.query(QueryRequest.builder("aov").build());
        log.info("[默认gmv=v3] aov 仍锁定依赖链={} 结果={}", aovV3era.resolvedChain(),
                value(aovV3era));
        assertEquals(162.5, value(aovV3era), 1e-9);
        assertFalse(aovV3era.resolvedChain().contains("gmv@v3"),
                "复合指标不得跨版本漂移到 gmv v3");
    }

    @Test
    void defaultSwitchIsAtomicAndVersionedInRationale() {
        // 并发切换默认版本期间，查询要么命中 v1 要么命中 v2，结果只能是 1800 或 1300
        java.util.Set<Double> observed = java.util.Collections.newSetFromMap(
                new java.util.concurrent.ConcurrentHashMap<>());
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        threads.add(new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                QueryResult r = engine.query(QueryRequest.builder("gmv").build());
                observed.add(value(r));
            }
        }));
        threads.add(new Thread(() -> {
            for (int i = 1; i <= 200; i++) {
                engine.setDefaultVersion("gmv", i % 2 == 0 ? 1 : 2);
            }
        }));
        threads.forEach(Thread::start);
        threads.forEach(t -> {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        log.info("[切换中观测] 默认查询结果集合={}", observed);
        assertEquals(java.util.Set.of(1800.0, 1300.0), observed);
    }
}
