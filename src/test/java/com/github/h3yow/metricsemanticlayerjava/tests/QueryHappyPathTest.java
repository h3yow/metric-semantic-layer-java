package com.github.h3yow.metricsemanticlayerjava.tests;

import com.github.h3yow.metricsemanticlayerjava.catalog.DemoCatalog;
import com.github.h3yow.metricsemanticlayerjava.engine.DefinitionValidator;
import com.github.h3yow.metricsemanticlayerjava.engine.QueryCache;
import com.github.h3yow.metricsemanticlayerjava.engine.QueryPlanner;
import com.github.h3yow.metricsemanticlayerjava.engine.RowEvaluator;
import com.github.h3yow.metricsemanticlayerjava.engine.SemanticEngine;
import com.github.h3yow.metricsemanticlayerjava.model.QueryRequest;
import com.github.h3yow.metricsemanticlayerjava.model.QueryResult;
import com.github.h3yow.metricsemanticlayerjava.model.SortOrder;
import com.github.h3yow.metricsemanticlayerjava.registry.MetricRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 正常路径：分组 / 过滤 / 排序 / having / 派生维度 / 复合指标 / 版本选择。
 * 每个用例打印输入、命中版本与判定依据。
 */
class QueryHappyPathTest {

    private static final Logger log = LoggerFactory.getLogger(QueryHappyPathTest.class);

    private SemanticEngine engine;

    @BeforeEach
    void setUp() {
        var registry = new MetricRegistry();
        var validator = new DefinitionValidator();
        var planner = new QueryPlanner(registry, new RowEvaluator());
        engine = new SemanticEngine(registry, validator, planner, new QueryCache());
        engine.bindDataSnapshot(DemoCatalog.buildSnapshot());
        DemoFixtures.load(engine);
    }

    private Map<String, Object> rowByDim(QueryResult r, String dim, Object value) {
        return r.rows().stream().filter(x -> value.equals(x.get(dim))).findFirst().orElseThrow();
    }

    @Test
    void gmvTwoVersionsDifferButAreStable() {
        // gmv v1 含退款：总额 1800；v2 剔除两笔退款 300+200：1300
        QueryRequest v1 = QueryRequest.builder("gmv").version(1).build();
        QueryRequest v2 = QueryRequest.builder("gmv").version(2).build();
        log.info("[输入] {} -> 期望 1800.0", v1);
        QueryResult r1 = engine.query(v1);
        log.info("[命中] v{} 依据={} 行={}", r1.metricVersion(), r1.rationale(), r1.rows());
        assertEquals(1800.0, (Double) r1.rows().get(0).get("gmv"), 1e-9);

        log.info("[输入] {} -> 期望 1300.0", v2);
        QueryResult r2 = engine.query(v2);
        log.info("[命中] v{} 依据={} 行={}", r2.metricVersion(), r2.rationale(), r2.rows());
        assertEquals(1300.0, (Double) r2.rows().get(0).get("gmv"), 1e-9);
    }

    @Test
    void groupByRegionWithDerivedMarket() {
        // 按物理列 region 分组，gmv v1
        QueryResult byRegion = engine.query(QueryRequest.builder("gmv").version(1)
                .dimensions(List.of("region")).orderBy("region").build());
        log.info("[输入] gmv v1 group by region；命中 v{}", byRegion.metricVersion());
        // CN: 200+50+300+180+150+0 = 880 ; US: 360+160+200+200 = 920
        assertEquals(880.0, (Double) rowByDim(byRegion, "region", "CN").get("gmv"), 1e-9);
        assertEquals(920.0, (Double) rowByDim(byRegion, "region", "US").get("gmv"), 1e-9);

        // 按派生维度 market 分组（境内=CN=880, 境外=US=920）
        QueryResult byMarket = engine.query(QueryRequest.builder("gmv").version(1)
                .dimensions(List.of("market")).orderBy("market").build());
        log.info("[输入] gmv v1 group by market；依赖链/依据={}", byMarket.rationale());
        assertEquals(880.0, (Double) rowByDim(byMarket, "market", "境内").get("gmv"), 1e-9);
        assertEquals(920.0, (Double) rowByDim(byMarket, "market", "境外").get("gmv"), 1e-9);
    }

    @Test
    void queryFilterAndHavingAndSortAndLimit() {
        // 叠加查询过滤：只看 app 渠道，按 region 分组，gmv v1
        QueryResult app = engine.query(QueryRequest.builder("gmv").version(1)
                .dimensions(List.of("region"))
                .filters(List.of("channel = 'app'"))
                .orderBy("gmv", SortOrder.DESC).build());
        log.info("[输入] gmv v1 region where channel=app order gmv desc；行={}", app.rows());
        // CN app: 200+300+150+0=650 ; US app: 360+200=560
        assertEquals("CN", app.rows().get(0).get("region"));
        assertEquals(650.0, (Double) app.rows().get(0).get("gmv"), 1e-9);
        assertEquals(560.0, (Double) app.rows().get(1).get("gmv"), 1e-9);

        // having：只保留 gmv >= 900 的市场（无维度，having 引用指标本身）
        QueryResult big = engine.query(QueryRequest.builder("gmv").version(2)
                .having("gmv >= 1300").build());
        log.info("[输入] gmv v2 having gmv>=1300；行={}", big.rows());
        assertEquals(1, big.rows().size());

        QueryResult none = engine.query(QueryRequest.builder("gmv").version(2)
                .having("gmv > 99999").build());
        assertEquals(0, none.rows().size());

        // limit
        QueryResult top1 = engine.query(QueryRequest.builder("gmv").version(1)
                .dimensions(List.of("region")).orderBy("gmv", SortOrder.DESC).limit(1).build());
        assertEquals(1, top1.rows().size());
    }

    @Test
    void compositeMetricUsesPinnedDependencies() {
        // aov v1 = gmv@2 / orders@1
        QueryResult all = engine.query(QueryRequest.builder("aov").build());
        log.info("[输入] aov 默认版本；命中 v{} 依赖链={}", all.metricVersion(), all.resolvedChain());
        // 非退款订单 8 笔（剔除 id 3,6），gmv v2 = 1300 → 162.5
        assertEquals(162.5, (Double) all.rows().get(0).get("aov"), 1e-9)
        ;
        assertTrue(all.resolvedChain().contains("gmv@v2"));
        assertTrue(all.resolvedChain().contains("orders@v1"));

        // 按 region：CN 非退款 200+50+180+150+0=580 / 5 笔(1,2,7,8,10) = 116
        QueryResult byRegion = engine.query(QueryRequest.builder("aov")
                .dimensions(List.of("region")).orderBy("region").build());
        log.info("[输入] aov group region；行={}", byRegion.rows());
        assertEquals(116.0, (Double) rowByDim(byRegion, "region", "CN").get("aov"), 1e-9);
    }

    @Test
    void rowDerivedMeasureAndYearDimension() {
        // gmv_q1only: 2024 一季度（year(biz_date)=2024 且 biz_date<=20240331）
        QueryResult q1 = engine.query(QueryRequest.builder("gmv_q1only").build());
        log.info("[输入] gmv_q1only 全表；行={}", q1.rows());
        // id1 200 + id2 50 + id3 300 + id4 360 = 910
        assertEquals(910.0, (Double) q1.rows().get(0).get("gmv_q1only"), 1e-9);

        // order_year 派生维度
        QueryResult byYear = engine.query(QueryRequest.builder("gmv").version(1)
                .dimensions(List.of("order_year")).orderBy("order_year").build());
        log.info("[输入] gmv v1 by year；行={}", byYear.rows());
        assertEquals(530.0, (Double) rowByDim(byYear, "order_year", 2023L).get("gmv"), 1e-9);
        assertEquals(1270.0, (Double) rowByDim(byYear, "order_year", 2024L).get("gmv"), 1e-9);
    }

    @Test
    void repeatedQueriesAreCachedAndStable() {
        QueryRequest req = QueryRequest.builder("gmv").version(1)
                .dimensions(List.of("region")).build();
        QueryResult a = engine.query(req);
        QueryResult b = engine.query(req);
        log.info("[重复查询] a.fromCache={} b.fromCache={} cacheHits={}",
                a.fromCache(), b.fromCache(), engine.cache().hitCount());
        assertEquals(a.rows(), b.rows());
        assertTrue(engine.cache().hitCount() >= 1);
    }
}
