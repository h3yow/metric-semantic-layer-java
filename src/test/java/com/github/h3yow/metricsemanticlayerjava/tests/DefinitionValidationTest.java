package com.github.h3yow.metricsemanticlayerjava.tests;

import com.github.h3yow.metricsemanticlayerjava.definition.Aggregation;
import com.github.h3yow.metricsemanticlayerjava.definition.Catalog;
import com.github.h3yow.metricsemanticlayerjava.definition.DerivedDimensionDef;
import com.github.h3yow.metricsemanticlayerjava.definition.MetricDef;
import com.github.h3yow.metricsemanticlayerjava.definition.MetricRef;
import com.github.h3yow.metricsemanticlayerjava.exception.CircularDependencyException;
import com.github.h3yow.metricsemanticlayerjava.exception.InvalidDefinitionException;
import com.github.h3yow.metricsemanticlayerjava.exception.MetricSemanticException;
import com.github.h3yow.metricsemanticlayerjava.exception.SelfReferenceException;
import com.github.h3yow.metricsemanticlayerjava.exception.TypeMismatchException;
import com.github.h3yow.metricsemanticlayerjava.exception.UnknownFieldException;
import com.github.h3yow.metricsemanticlayerjava.exception.UnknownMetricException;
import com.github.h3yow.metricsemanticlayerjava.exception.UnknownVersionException;
import com.github.h3yow.metricsemanticlayerjava.local.LocalTables;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.col;
import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.eq;
import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.lit;
import static com.github.h3yow.metricsemanticlayerjava.expr.Expressions.plus;
import static com.github.h3yow.metricsemanticlayerjava.local.LocalTables.ORDERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Malformed definitions must be rejected with distinct, distinguishable reasons,
 * never silently coerced to null/zero.
 */
class DefinitionValidationTest {

    private static final Logger log = LoggerFactory.getLogger(DefinitionValidationTest.class);

    private Map<String, com.github.h3yow.metricsemanticlayerjava.model.TableData> tables() {
        return LocalTables.snapshot(1L, 5L).tables();
    }

    private void assertRejected(String input, Class<? extends MetricSemanticException> expected,
                                Runnable build) {
        MetricSemanticException ex = assertThrows(expected, build::run);
        log.info("[定义校验] 输入={} | 判定依据=code:{} -> {}", input, ex.code(), ex.getMessage());
        assertEquals(expected.getSimpleName(), ex.code());
    }

    @Test
    void unknownPhysicalFieldInMeasure() {
        assertRejected("base metric measure=col(non_existent)", UnknownFieldException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addMetric(new MetricDef.BaseMetricDef("bad", 1, "x", ORDERS,
                                Aggregation.SUM, col("non_existent"), null))
                        .build());
    }

    @Test
    void unknownFieldInFilter() {
        assertRejected("base metric filter=col(ghost)=true", UnknownFieldException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addMetric(new MetricDef.BaseMetricDef("bad", 1, "x", ORDERS,
                                Aggregation.COUNT, null, eq(col("ghost"), lit(true))))
                        .build());
    }

    @Test
    void typeMismatchSumOverString() {
        assertRejected("SUM(col(region:string))", TypeMismatchException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addMetric(new MetricDef.BaseMetricDef("bad", 1, "x", ORDERS,
                                Aggregation.SUM, col("region"), null))
                        .build());
    }

    @Test
    void typeMismatchCompareDifferentTypes() {
        assertRejected("col(amount:double) = col(region:string)", TypeMismatchException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addMetric(new MetricDef.BaseMetricDef("bad", 1, "x", ORDERS,
                                Aggregation.COUNT, null, eq(col("amount"), col("region"))))
                        .build());
    }

    @Test
    void typeMismatchArithmeticOnString() {
        assertRejected("col(region:string) + lit(1)", TypeMismatchException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addMetric(new MetricDef.BaseMetricDef("bad", 1, "x", ORDERS,
                                Aggregation.SUM, plus(col("region"), lit(1L)), null))
                        .build());
    }

    @Test
    void filterMustBeBoolean() {
        assertRejected("filter=col(amount:double)", TypeMismatchException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addMetric(new MetricDef.BaseMetricDef("bad", 1, "x", ORDERS,
                                Aggregation.SUM, col("amount"), col("amount")))
                        .build());
    }

    @Test
    void unknownTable() {
        assertRejected("base metric table=ghost_table", InvalidDefinitionException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addMetric(new MetricDef.BaseMetricDef("bad", 1, "x", "ghost_table",
                                Aggregation.COUNT, null, null))
                        .build());
    }

    @Test
    void sumWithoutMeasure() {
        assertRejected("SUM without measure", InvalidDefinitionException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addMetric(new MetricDef.BaseMetricDef("bad", 1, "x", ORDERS,
                                Aggregation.SUM, null, null))
                        .build());
    }

    @Test
    void derivedDimensionUnknownTable() {
        assertRejected("derived dim table=ghost", InvalidDefinitionException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addDimension(new DerivedDimensionDef("ghost", "d", lit(1L)))
                        .build());
    }

    @Test
    void derivedDimensionSelfReference() {
        assertRejected("derived dim d = col(d) + 1", SelfReferenceException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addDimension(new DerivedDimensionDef(
                                ORDERS, "d", plus(col("d"), lit(1L))))
                        .build());
    }

    @Test
    void derivedMetricSelfReference() {
        assertRejected("metric m v1 depends on m[v1]", SelfReferenceException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addMetric(new MetricDef.DerivedMetricDef("m", 1, "x",
                                col("m"), List.of(new MetricRef("m", 1))))
                        .build());
    }

    @Test
    void derivedMetricUnknownDependency() {
        assertRejected("metric m depends on ghost[v1]", UnknownMetricException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addMetric(new MetricDef.DerivedMetricDef("m", 1, "x",
                                col("ghost"), List.of(new MetricRef("ghost", 1))))
                        .build());
    }

    @Test
    void derivedMetricUnknownDependencyVersion() {
        Catalog base = Catalog.builder(1).withTables(tables())
                .addMetric(new MetricDef.BaseMetricDef("a", 1, "x", ORDERS,
                        Aggregation.COUNT, null, null))
                .build();
        assertEquals(1, base.defaultVersionOf("a"));
        assertRejected("metric m depends on a[v9]", UnknownVersionException.class, () ->
                Catalog.builder(2).withTables(tables())
                        .addMetric(new MetricDef.BaseMetricDef("a", 1, "x", ORDERS,
                                Aggregation.COUNT, null, null))
                        .addMetric(new MetricDef.DerivedMetricDef("m", 1, "x",
                                col("a"), List.of(new MetricRef("a", 9))))
                        .build());
    }

    @Test
    void derivedFormulaReferencesUndeclaredName() {
        assertRejected("formula col(a)+col(b) but only a declared", UnknownFieldException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addMetric(new MetricDef.BaseMetricDef("a", 1, "x", ORDERS,
                                Aggregation.COUNT, null, null))
                        .addMetric(new MetricDef.DerivedMetricDef("m", 1, "x",
                                plus(col("a"), col("b")),
                                List.of(new MetricRef("a", 1))))
                        .build());
    }

    @Test
    void derivedFormulaTypeMismatch() {
        assertRejected("COUNT(long) + region-free STRING via metric types",
                TypeMismatchException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addMetric(new MetricDef.BaseMetricDef("a", 1, "x", ORDERS,
                                Aggregation.COUNT, null, null))
                        .addMetric(new MetricDef.BaseMetricDef("b", 1, "x", ORDERS,
                                Aggregation.MAX, col("region"), null))
                        .addMetric(new MetricDef.DerivedMetricDef("m", 1, "x",
                                plus(col("a"), col("b")),
                                List.of(new MetricRef("a", 1), new MetricRef("b", 1))))
                        .build());
    }

    @Test
    void circularMetricDependency() {
        CircularDependencyException ex = assertThrows(CircularDependencyException.class, () ->
                Catalog.builder(1).withTables(tables())
                        .addMetric(new MetricDef.DerivedMetricDef("a", 1, "x", col("b"),
                                List.of(new MetricRef("b", 1))))
                        .addMetric(new MetricDef.DerivedMetricDef("b", 1, "x", col("c"),
                                List.of(new MetricRef("c", 1))))
                        .addMetric(new MetricDef.DerivedMetricDef("c", 1, "x", col("a"),
                                List.of(new MetricRef("a", 1))))
                        .build());
        log.info("[定义校验] 输入=a->b->c->a | 判定依据=code:{} cycle={}",
                ex.code(), ex.cycle());
        assertTrue(ex.cycle().size() >= 4);
    }

    @Test
    void twoReasonsAreDistinguishable() {
        try {
            Catalog.builder(1).withTables(tables())
                    .addMetric(new MetricDef.BaseMetricDef("bad", 1, "x", ORDERS,
                            Aggregation.SUM, col("nope"), null))
                    .build();
        } catch (MetricSemanticException e) {
            assertEquals("UnknownFieldException", e.code());
            assertNot(e.code(), TypeMismatchException.class.getSimpleName());
        }
    }

    private static void assertNot(String actual, String other) {
        org.junit.jupiter.api.Assertions.assertNotEquals(other, actual);
    }
}
