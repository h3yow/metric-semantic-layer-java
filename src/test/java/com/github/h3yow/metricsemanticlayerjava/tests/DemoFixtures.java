package com.github.h3yow.metricsemanticlayerjava.tests;

import com.github.h3yow.metricsemanticlayerjava.engine.SemanticEngine;
import com.github.h3yow.metricsemanticlayerjava.model.AggType;
import com.github.h3yow.metricsemanticlayerjava.model.DataType;
import com.github.h3yow.metricsemanticlayerjava.model.DerivedDimension;
import com.github.h3yow.metricsemanticlayerjava.model.MetricDefinition;

/**
 * 演示定义装载（与 CatalogBootstrap 保持一致，供单测直接使用，免去 Spring 启动开销）。
 */
final class DemoFixtures {

    private DemoFixtures() {
    }

    static void load(SemanticEngine engine) {
        engine.registerDimension(new DerivedDimension(
                "market", "orders",
                "case_when(region = 'CN', '境内', '境外')",
                DataType.STRING));
        engine.registerDimension(new DerivedDimension(
                "order_year", "orders", "year(biz_date)", DataType.LONG));

        engine.registerMetric(new MetricDefinition(
                "gmv", 1, "orders", "amount", AggType.SUM, null, null,
                "v1 成交总额：含退款"));
        engine.registerMetric(new MetricDefinition(
                "gmv", 2, "orders", "amount", AggType.SUM,
                "is_refund = false", null,
                "v2 成交总额：剔除退款"));
        engine.registerMetric(new MetricDefinition(
                "orders", 1, "orders", null, AggType.COUNT,
                "is_refund = false", null,
                "有效订单数（剔除退款）"));
        engine.registerMetric(new MetricDefinition(
                "aov", 1, "orders", null, AggType.COUNT,
                null, "gmv@2 / orders@1",
                "客单价：gmv v2 / orders v1"));
        engine.registerMetric(new MetricDefinition(
                "gmv_q1only", 1, "orders", null, AggType.SUM,
                null, "case_when(year(biz_date) = 2024 and biz_date <= 20240331, amount)",
                "仅统计 2024 一季度成交金额"));
    }
}
