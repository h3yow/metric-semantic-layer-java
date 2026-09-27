package com.github.h3yow.metricsemanticlayerjava.local;

import com.github.h3yow.metricsemanticlayerjava.definition.Aggregation;
import com.github.h3yow.metricsemanticlayerjava.definition.Catalog;
import com.github.h3yow.metricsemanticlayerjava.definition.DerivedDimensionDef;
import com.github.h3yow.metricsemanticlayerjava.definition.MetricDef;
import com.github.h3yow.metricsemanticlayerjava.definition.MetricRef;
import com.github.h3yow.metricsemanticlayerjava.model.TableData;

import java.util.List;
import java.util.Map;

import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.and;
import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.col;
import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.cond;
import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.eq;
import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.lit;
import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.minus;
import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.plus;
import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.times;
import static com.github.h3yow.metricsemanticlayerjava.local.LocalTables.ORDERS;

/**
 * Declarative demo definitions. The interesting part is metric versioning:
 *
 * <ul>
 *   <li>{@code revenue v1}: SUM(amount) over all orders (refunds included);</li>
 *   <li>{@code revenue v2}: SUM(amount) over non-refunded orders (new口径);</li>
 *   <li>{@code gross_profit v1} depends on the pinned {@code revenue[v1]};</li>
 *   <li>{@code gross_profit v2} depends on the pinned {@code revenue[v2]};</li>
 * </ul>
 * changing revenue's default version from 1 to 2 changes neither
 * {@code gross_profit v1}'s lineage nor any query that explicitly pins v1.
 */
public final class LocalDemoCatalog {

    public static final String REVENUE = "revenue";
    public static final String COST = "cost";
    public static final String ORDER_COUNT = "order_count";
    public static final String AVG_ORDER_VALUE = "avg_order_value";
    public static final String GROSS_PROFIT = "gross_profit";
    public static final String WEB_APP_GMV = "web_app_gmv";

    private LocalDemoCatalog() {
    }

    /** First published dictionary: refunded orders counted in revenue. */
    public static Catalog revision1(Map<String, TableData> tables) {
        Catalog.Builder b = Catalog.builder(1).withTables(tables);

        b.addMetric(new MetricDef.BaseMetricDef(REVENUE, 1, "GMV incl. refunds",
                ORDERS, Aggregation.SUM, col("amount"), null));
        b.addMetric(new MetricDef.BaseMetricDef(COST, 1, "Total cost",
                ORDERS, Aggregation.SUM, col("cost"), null));
        b.addMetric(new MetricDef.BaseMetricDef(ORDER_COUNT, 1, "Order rows",
                ORDERS, Aggregation.COUNT, null, null));
        b.addMetric(new MetricDef.BaseMetricDef(AVG_ORDER_VALUE, 1, "Average order value",
                ORDERS, Aggregation.AVG, col("amount"), null));
        b.addMetric(new MetricDef.DerivedMetricDef(GROSS_PROFIT, 1, "Revenue v1 - cost",
                minus(col(REVENUE), col(COST)),
                List.of(new MetricRef(REVENUE, 1), new MetricRef(COST, 1))));
        addChannelGroupDimension(b);
        return b.build();
    }

    /** Second dictionary: revenue v2 excludes refunds; default switches to v2. */
    public static Catalog revision2(Map<String, TableData> tables) {
        Catalog.Builder b = Catalog.builder(2).withTables(tables);

        b.addMetric(new MetricDef.BaseMetricDef(REVENUE, 1, "GMV incl. refunds",
                ORDERS, Aggregation.SUM, col("amount"), null));
        b.addMetric(new MetricDef.BaseMetricDef(REVENUE, 2, "GMV excl. refunds",
                ORDERS, Aggregation.SUM, col("amount"), col("is_refunded").eq(lit(false))));
        b.addMetric(new MetricDef.BaseMetricDef(COST, 1, "Total cost",
                ORDERS, Aggregation.SUM, col("cost"), null));
        b.addMetric(new MetricDef.BaseMetricDef(ORDER_COUNT, 1, "Order rows",
                ORDERS, Aggregation.COUNT, null, null));
        b.addMetric(new MetricDef.BaseMetricDef(ORDER_COUNT, 2, "Paid (non-refunded) order rows",
                ORDERS, Aggregation.COUNT, null, col("is_refunded").eq(lit(false))));
        b.addMetric(new MetricDef.BaseMetricDef(AVG_ORDER_VALUE, 1, "Average order value",
                ORDERS, Aggregation.AVG, col("amount"), null));
        b.addMetric(new MetricDef.BaseMetricDef(AVG_ORDER_VALUE, 2, "Average paid order value",
                ORDERS, Aggregation.AVG, col("amount"), col("is_refunded").eq(lit(false))));

        b.addMetric(new MetricDef.DerivedMetricDef(GROSS_PROFIT, 1, "Revenue v1 - cost (frozen)",
                minus(col(REVENUE), col(COST)),
                List.of(new MetricRef(REVENUE, 1), new MetricRef(COST, 1))));
        b.addMetric(new MetricDef.DerivedMetricDef(GROSS_PROFIT, 2, "Revenue v2 - cost",
                minus(col(REVENUE), col(COST)),
                List.of(new MetricRef(REVENUE, 2), new MetricRef(COST, 1))));
        // derived metric over another (pinned) versioned metric
        b.addMetric(new MetricDef.DerivedMetricDef(WEB_APP_GMV, 1, "GMV v2 passthrough",
                times(col(REVENUE), lit(1.0)),
                List.of(new MetricRef(REVENUE, 2))));
        // extra filtered base metrics exercising AND/comparison/arithmetic
        b.addMetric(new MetricDef.BaseMetricDef("cn_web_revenue", 1, "CN web revenue excl. refunds",
                ORDERS, Aggregation.SUM, col("amount"),
                and(eq(col("is_refunded"), lit(false)), eq(col("region"), lit("CN")))));
        b.addMetric(new MetricDef.BaseMetricDef("quantity_total", 1, "App quantity",
                ORDERS, Aggregation.SUM, plus(col("quantity"), lit(0L)),
                eq(col("channel"), lit("app"))));

        addChannelGroupDimension(b);
        b.defaultVersion(REVENUE, 2);
        b.defaultVersion(ORDER_COUNT, 2);
        b.defaultVersion(AVG_ORDER_VALUE, 2);
        b.defaultVersion(GROSS_PROFIT, 2);
        return b.build();
    }

    /** Derived dimension: map physical channel into a coarser grouping. */
    private static void addChannelGroupDimension(Catalog.Builder b) {
        var online = eq(col("channel"), lit("web")).or(eq(col("channel"), lit("app")));
        b.addDimension(new DerivedDimensionDef(
                ORDERS, "channel_group",
                cond(online, lit("online"), lit("offline"))));
    }
}
