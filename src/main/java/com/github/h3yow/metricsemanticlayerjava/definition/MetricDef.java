package com.github.h3yow.metricsemanticlayerjava.definition;

import com.github.h3yow.metricsemanticlayerjava.exception.InvalidDefinitionException;
import com.github.h3yow.metricsemanticlayerjava.expr.Expr;

import java.util.List;

/**
 * A metric definition at one exact version. Two shapes exist:
 * <ul>
 *   <li>{@link BaseMetricDef}: aggregation (+ optional filter/measure) over a physical table;</li>
 *   <li>{@link DerivedMetricDef}: a formula over other, explicitly versioned metrics.</li>
 * </ul>
 */
public sealed interface MetricDef permits MetricDef.BaseMetricDef, MetricDef.DerivedMetricDef {

    String name();

    int version();

    String label();

    /** Other metrics this definition depends on (empty for base metrics). */
    List<MetricRef> dependencies();

    /** Aggregation over one physical table. */
    record BaseMetricDef(String name,
                         int version,
                         String label,
                         String table,
                         Aggregation aggregation,
                         Expr measure,
                         Expr filter) implements MetricDef {

        public BaseMetricDef {
            checkName(name, version);
            if (table == null || table.isBlank()) {
                throw new InvalidDefinitionException("base metric '" + name + "' must declare a table");
            }
            if (aggregation == null) {
                throw new InvalidDefinitionException("base metric '" + name + "' must declare an aggregation");
            }
        }

        @Override
        public List<MetricRef> dependencies() {
            return List.of();
        }

        /** COUNT without a measure counts rows. */
        public boolean countsRows() {
            return measure == null;
        }
    }

    /** Formula over other metrics; every dependency carries an explicit version. */
    record DerivedMetricDef(String name,
                            int version,
                            String label,
                            Expr formula,
                            List<MetricRef> dependencies) implements MetricDef {

        public DerivedMetricDef {
            checkName(name, version);
            if (formula == null) {
                throw new InvalidDefinitionException(
                        "derived metric '" + name + "' v" + version + " must declare a formula");
            }
            dependencies = List.copyOf(dependencies);
        }

        /** Dependency names referenced by the formula; two refs must not share a name. */
        public List<String> dependencyNames() {
            return dependencies.stream().map(MetricRef::metric).toList();
        }
    }

    private static void checkName(String name, int version) {
        if (name == null || name.isBlank()) {
            throw new InvalidDefinitionException("metric name must not be blank");
        }
        if (version <= 0) {
            throw new InvalidDefinitionException(
                    "metric '" + name + "' version must be positive, got " + version);
        }
    }
}
