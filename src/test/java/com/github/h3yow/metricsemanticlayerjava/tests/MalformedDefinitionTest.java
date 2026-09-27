package com.github.h3yow.metricsemanticlayerjava.tests;

import com.github.h3yow.metricsemanticlayerjava.catalog.DemoCatalog;
import com.github.h3yow.metricsemanticlayerjava.engine.DefinitionValidator;
import com.github.h3yow.metricsemanticlayerjava.engine.QueryCache;
import com.github.h3yow.metricsemanticlayerjava.engine.QueryPlanner;
import com.github.h3yow.metricsemanticlayerjava.engine.RowEvaluator;
import com.github.h3yow.metricsemanticlayerjava.engine.SemanticEngine;
import com.github.h3yow.metricsemanticlayerjava.error.CircularDependencyException;
import com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException;
import com.github.h3yow.metricsemanticlayerjava.error.MalformedExpressionException;
import com.github.h3yow.metricsemanticlayerjava.error.MetricNotFoundException;
import com.github.h3yow.metricsemanticlayerjava.error.SelfReferenceException;
import com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException;
import com.github.h3yow.metricsemanticlayerjava.error.UnknownFieldException;
import com.github.h3yow.metricsemanticlayerjava.error.VersionNotFoundException;
import com.github.h3yow.metricsemanticlayerjava.model.AggType;
import com.github.h3yow.metricsemanticlayerjava.model.DataType;
import com.github.h3yow.metricsemanticlayerjava.model.DerivedDimension;
import com.github.h3yow.metricsemanticlayerjava.model.MetricDefinition;
import com.github.h3yow.metricsemanticlayerjava.model.QueryRequest;
import com.github.h3yow.metricsemanticlayerjava.registry.MetricRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 边界一：畸形定义必须被分类拒绝，异常类型彼此可区分，绝不静默按 null/0 处理。
 */
class MalformedDefinitionTest {

    private static final Logger log = LoggerFactory.getLogger(MalformedDefinitionTest.class);

    private SemanticEngine engine;

    private void expectRejected(String label, Class<? extends Throwable> type, Runnable action) {
        Throwable t = assertThrows(type, action::run, "应当拒绝: " + label);
        log.info("[拒绝] 场景='{}' 分类={} 原因={}", label, type.getSimpleName(), t.getMessage());
    }

    @BeforeEach
    void setUp() {
        var registry = new MetricRegistry();
        var planner = new QueryPlanner(registry, new RowEvaluator());
        engine = new SemanticEngine(registry, new DefinitionValidator(), planner, new QueryCache());
        engine.bindDataSnapshot(DemoCatalog.buildSnapshot());
    }


    @Test
    void unknownFieldsAreRejected() {
        expectRejected("指标源列不存在", UnknownFieldException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "m1", 1, "orders", "not_exist_col", AggType.SUM,
                        null, null, null)));
        expectRejected("过滤引用未知列", UnknownFieldException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "m2", 1, "orders", "amount", AggType.SUM,
                        "ghost_col > 1", null, null)));
        expectRejected("派生表达式引用未知列", UnknownFieldException.class,
                () -> engine.registerDimension(new DerivedDimension(
                        "d1", "orders", "ghost * 2", DataType.LONG)));
        expectRejected("查询分组用未知维度", UnknownFieldException.class,
                () -> {
                    DemoFixtures.load(engine);
                    engine.query(QueryRequest.builder("gmv").version(1)
                            .dimensions(java.util.List.of("no_such_dim")).build());
                });
        expectRejected("来源表不存在", InvalidDefinitionException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "m3", 1, "no_table", "amount", AggType.SUM,
                        null, null, null)));
    }

    @Test
    void typeMismatchesAreRejected() {
        expectRejected("SUM 字符串列", TypeMismatchException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "t1", 1, "orders", "region", AggType.SUM,
                        null, null, null)));
        expectRejected("字符串参与乘法", TypeMismatchException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "t2", 1, "orders", null, AggType.SUM,
                        null, "region * 2", null)));
        expectRejected("过滤条件非布尔(数值)", TypeMismatchException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "t3", 1, "orders", "amount", AggType.SUM,
                        "amount + 1", null, null)));
        expectRejected("派生维度声明类型与推断不符", TypeMismatchException.class,
                () -> engine.registerDimension(new DerivedDimension(
                        "d2", "orders", "region", DataType.LONG)));
        expectRejected("having 非布尔", TypeMismatchException.class,
                () -> {
                    DemoFixtures.load(engine);
                    engine.query(QueryRequest.builder("gmv").version(1).having("amount + 1").build());
                });
    }

    @Test
    void malformedExpressionsAreRejected() {
        expectRejected("括号不匹配", MalformedExpressionException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "x1", 1, "orders", null, AggType.SUM,
                        null, "(amount + 1", null)));
        expectRejected("运算符残缺", MalformedExpressionException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "x2", 1, "orders", "amount", AggType.SUM,
                        "amount >", null, null)));
        expectRejected("未知函数", InvalidDefinitionException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "x3", 1, "orders", null, AggType.SUM,
                        null, "no_such_fn(amount)", null)));
        expectRejected("@后非整数版本", MalformedExpressionException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "x4", 1, "orders", null, AggType.COUNT,
                        null, "gmv@abc", null)));
    }

    @Test
    void selfAndCircularReferencesAreRejected() {
        // a@2 直接引用自身
        engine.registerMetric(new MetricDefinition(
                "a", 1, "orders", "amount", AggType.SUM, null, null, null));
        expectRejected("指标直接自引用", SelfReferenceException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "a", 2, "orders", null, AggType.COUNT,
                        null, "a@2", null)));

        // 构造环：c@1 -> d@1 -> c@1；允许前向引用逐个注册，全量校验时拒绝
        engine.registerMetric(new MetricDefinition(
                "c", 1, "orders", null, AggType.COUNT, null, "d@1", null));
        engine.registerMetric(new MetricDefinition(
                "d", 1, "orders", null, AggType.COUNT, null, "c@1", null));
        expectRejected("两指标互相依赖成环(validateAll)", CircularDependencyException.class,
                () -> engine.validateAll());
        // 查询实际触达环时同样拒绝（惰性闭包检测）
        expectRejected("查询触达循环依赖", CircularDependencyException.class,
                () -> engine.query(QueryRequest.builder("c").build()));
    }

    @Test
    void missingMetricAndVersionAreRejected() {
        DemoFixtures.load(engine);
        expectRejected("查询不存在的指标", MetricNotFoundException.class,
                () -> engine.query(QueryRequest.builder("ghost_metric").build()));
        expectRejected("查询不存在的版本", VersionNotFoundException.class,
                () -> engine.query(QueryRequest.builder("gmv").version(99).build()));
        expectRejected("依赖不存在的指标版本(查询触达)", VersionNotFoundException.class,
                () -> {
                    engine.registerMetric(new MetricDefinition(
                            "y1", 1, "orders", null, AggType.COUNT,
                            null, "gmv@99", null));
                    engine.query(QueryRequest.builder("y1").build());
                });
    }

    @Test
    void duplicateRegistrationAndStructuralErrorsRejected() {
        DemoFixtures.load(engine);
        expectRejected("重复注册同版本", InvalidDefinitionException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "gmv", 1, "orders", "amount", AggType.SUM,
                        null, null, null)));
        expectRejected("SUM 既无源列也无派生表达式", InvalidDefinitionException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "s1", 1, "orders", null, AggType.SUM,
                        null, null, null)));
        expectRejected("派生维度引用指标", InvalidDefinitionException.class,
                () -> engine.registerDimension(new DerivedDimension(
                        "d3", "orders", "gmv@1", DataType.DOUBLE)));
        expectRejected("派生维度与物理列重名", InvalidDefinitionException.class,
                () -> engine.registerDimension(new DerivedDimension(
                        "region", "orders", "'X'", DataType.STRING)));
        expectRejected("过滤中引用指标", InvalidDefinitionException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "s2", 1, "orders", "amount", AggType.SUM,
                        "gmv@1 > 0", null, null)));
        expectRejected("切换到不存在的默认版本", VersionNotFoundException.class,
                () -> engine.setDefaultVersion("gmv", 77));
    }

    @Test
    void failedPublicationLeavesRegistryConsistent() {
        DemoFixtures.load(engine);
        long genBefore = engine.registry().generation();

        // 1) 注册一个引用缺失指标的定义：validateAll 能识别，查询不可用
        engine.registerMetric(new MetricDefinition(
                "partial", 1, "orders", null, AggType.COUNT, null, "missing@1", null));
        expectRejected("全量校验发现缺失依赖", MetricNotFoundException.class,
                () -> engine.validateAll());
        expectRejected("查询缺失依赖指标被拒绝", MetricNotFoundException.class,
                () -> engine.query(QueryRequest.builder("partial").build()));

        // 2) 畸形定义注册失败：注册表代际不变、无半成品
        assertThrows(UnknownFieldException.class,
                () -> engine.registerMetric(new MetricDefinition(
                        "bad", 1, "orders", "no_col", AggType.SUM, null, null, null)));
        assertEquals(genBefore + 1, engine.registry().generation(),
                "失败注册不应推进代际");
        assertThrows(MetricNotFoundException.class,
                () -> engine.query(QueryRequest.builder("bad").build()));

        // 3) 既有指标仍稳定可查
        assertEquals(1800.0,
                (Double) engine.query(QueryRequest.builder("gmv").version(1).build())
                        .rows().get(0).get("gmv"), 1e-9);
        log.info("[一致性] 缺失依赖可注册但不可查询；畸形注册不改代际；gmv 仍=1800.0");
    }
}
