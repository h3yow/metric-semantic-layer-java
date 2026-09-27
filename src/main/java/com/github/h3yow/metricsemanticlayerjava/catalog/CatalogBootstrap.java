package com.github.h3yow.metricsemanticlayerjava.catalog;

import com.github.h3yow.metricsemanticlayerjava.engine.DefinitionValidator;
import com.github.h3yow.metricsemanticlayerjava.engine.QueryCache;
import com.github.h3yow.metricsemanticlayerjava.engine.QueryPlanner;
import com.github.h3yow.metricsemanticlayerjava.engine.RowEvaluator;
import com.github.h3yow.metricsemanticlayerjava.engine.SemanticEngine;
import com.github.h3yow.metricsemanticlayerjava.model.AggType;
import com.github.h3yow.metricsemanticlayerjava.model.DataType;
import com.github.h3yow.metricsemanticlayerjava.model.DerivedDimension;
import com.github.h3yow.metricsemanticlayerjava.model.MetricDefinition;
import com.github.h3yow.metricsemanticlayerjava.registry.MetricRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 组装语义层各组件，装载本地演示数据，并注册演示指标 / 维度。
 */
@Configuration
public class CatalogBootstrap {

    private static final Logger log = LoggerFactory.getLogger(CatalogBootstrap.class);

    @Bean
    public MetricRegistry metricRegistry() {
        return new MetricRegistry();
    }

    @Bean
    public RowEvaluator rowEvaluator() {
        return new RowEvaluator();
    }

    @Bean
    public DefinitionValidator definitionValidator() {
        return new DefinitionValidator();
    }

    @Bean
    public QueryPlanner queryPlanner(MetricRegistry registry, RowEvaluator evaluator) {
        return new QueryPlanner(registry, evaluator);
    }

    @Bean
    public QueryCache queryCache() {
        return new QueryCache();
    }

    @Bean
    public SemanticEngine semanticEngine(MetricRegistry registry,
                                         DefinitionValidator validator,
                                         QueryPlanner planner,
                                         QueryCache cache) {
        SemanticEngine engine = new SemanticEngine(registry, validator, planner, cache);
        engine.bindDataSnapshot(DemoCatalog.buildSnapshot());
        loadDefinitions(engine);
        return engine;
    }

    /**
     * 演示口径：
     * <ul>
     *   <li>gmv v1：成交总额（含退款）；gmv v2：剔除退款——同指标两个口径版本；</li>
     *   <li>orders v1：订单数（剔除退款）；</li>
     *   <li>aov v1：客单价 = gmv@2 / orders@1（依赖固定版本，防止跨版本混用）；</li>
     *   <li>gmv_q1only v1：gmv v1 的季度过滤口径变体；</li>
     *   <li>派生维度 market（境内/境外）、order_year。</li>
     * </ul>
     */
    private void loadDefinitions(SemanticEngine engine) {
        // 派生维度
        engine.registerDimension(new DerivedDimension(
                "market", "orders",
                "case_when(region = 'CN', '境内', '境外')",
                DataType.STRING));
        engine.registerDimension(new DerivedDimension(
                "order_year", "orders",
                "year(biz_date)",
                DataType.LONG));

        // gmv v1：成交总额（含退款，老口径）
        engine.registerMetric(new MetricDefinition(
                "gmv", 1, "orders", "amount", AggType.SUM,
                null, null,
                "v1 成交总额：所有订单 amount 求和，含退款"));

        // gmv v2：剔除退款（新口径）
        engine.registerMetric(new MetricDefinition(
                "gmv", 2, "orders", "amount", AggType.SUM,
                "is_refund = false", null,
                "v2 成交总额：剔除退款订单"));

        // orders v1：订单数（剔除退款）
        engine.registerMetric(new MetricDefinition(
                "orders", 1, "orders", null, AggType.COUNT,
                "is_refund = false", null,
                "有效订单数（剔除退款）"));

        // aov v1：复合指标，依赖固定版本
        engine.registerMetric(new MetricDefinition(
                "aov", 1, "orders", null, AggType.COUNT,
                null, "gmv@2 / orders@1",
                "客单价：剔除退款口径的 gmv v2 / 有效订单数 v1"));

        // gmv_q1only v1：表达式过滤变体（演示行级派生度量，非一季度行得到 null 被聚合跳过）
        engine.registerMetric(new MetricDefinition(
                "gmv_q1only", 1, "orders", null, AggType.SUM,
                null, "case_when(year(biz_date) = 2024 and biz_date <= 20240331, amount)",
                "仅统计 2024 一季度的成交金额（老 gmv 口径）"));

        log.info("演示指标与派生维度装载完成");
    }
}
