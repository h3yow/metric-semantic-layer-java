package com.github.h3yow.metricsemanticlayerjava.definition;

import com.github.h3yow.metricsemanticlayerjava.exception.CircularDependencyException;
import com.github.h3yow.metricsemanticlayerjava.exception.InvalidDefinitionException;
import com.github.h3yow.metricsemanticlayerjava.exception.SelfReferenceException;
import com.github.h3yow.metricsemanticlayerjava.exception.TypeMismatchException;
import com.github.h3yow.metricsemanticlayerjava.exception.UnknownFieldException;
import com.github.h3yow.metricsemanticlayerjava.exception.UnknownMetricException;
import com.github.h3yow.metricsemanticlayerjava.exception.UnknownVersionException;
import com.github.h3yow.metricsemanticlayerjava.expr.Expr;
import com.github.h3yow.metricsemanticlayerjava.expr.Scope;
import com.github.h3yow.metricsemanticlayerjava.model.DataType;
import com.github.h3yow.metricsemanticlayerjava.model.FieldDef;
import com.github.h3yow.metricsemanticlayerjava.model.TableData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, fully validated metric catalog (a metric "semantic dictionary").
 *
 * <p>A catalog is created atomically by {@link #builder(long)}: the new catalog
 * becomes visible only after <em>every</em> definition has been type-checked and
 * every dependency resolved. Readers therefore never observe half-edited
 * definitions.
 */
public final class Catalog {

    private final long revision;
    private final Map<String, NavigableVersions> metrics;
    private final Map<String, List<DerivedDimensionDef>> dimensionsByTable;
    private final Map<String, DataType> metricType;
    private final Map<String, TableData> tables;

    private Catalog(long revision,
                    Map<String, NavigableVersions> metrics,
                    Map<String, List<DerivedDimensionDef>> dimensionsByTable,
                    Map<String, DataType> metricType,
                    Map<String, TableData> tables) {
        this.revision = revision;
        this.metrics = metrics;
        this.dimensionsByTable = dimensionsByTable;
        this.metricType = metricType;
        this.tables = tables;
    }

    public long revision() {
        return revision;
    }

    public Set<String> metricNames() {
        return Collections.unmodifiableSet(metrics.keySet());
    }

    public MetricDef resolve(String name, Integer version) {
        NavigableVersions v = metrics.get(name);
        if (v == null) {
            throw new UnknownMetricException(name);
        }
        int ver = version != null ? version : v.defaultVersion;
        MetricDef def = v.byVersion.get(ver);
        if (def == null) {
            throw new UnknownVersionException(name, ver);
        }
        return def;
    }

    public int defaultVersionOf(String name) {
        NavigableVersions v = metrics.get(name);
        if (v == null) {
            throw new UnknownMetricException(name);
        }
        return v.defaultVersion;
    }

    public List<Integer> versionsOf(String name) {
        NavigableVersions v = metrics.get(name);
        if (v == null) {
            throw new UnknownMetricException(name);
        }
        return v.byVersion.navigableKeySet().stream().toList();
    }

    public DataType resultTypeOf(String name, int version) {
        DataType t = metricType.get(name + "#" + version);
        if (t == null) {
            throw new UnknownVersionException(name, version);
        }
        return t;
    }

    /** Derived dimensions of one table, in declared (dependency) order. */
    public List<DerivedDimensionDef> dimensionsOf(String table) {
        return dimensionsByTable.getOrDefault(table, List.of());
    }

    // ---------------------------------------------------------------- builder

    public static Builder builder(long revision) {
        return new Builder(revision);
    }

    /** All names visible on a table: physical columns plus derived dimensions bound so far. */
    private static Scope tableScope(Map<String, DataType> fields) {
        return name -> Optional.ofNullable(fields.get(name));
    }

    public static final class Builder {
        private final long revision;
        private final Map<String, NavigableVersions> metrics = new LinkedHashMap<>();
        private final Map<String, List<DerivedDimensionDef>> dims = new LinkedHashMap<>();
        private final Map<String, DataType> metricType = new HashMap<>();
        private Map<String, TableData> tables = Map.of();
        private final Set<String> defaultsSet = new HashSet<>();

        private Builder(long revision) {
            this.revision = revision;
        }

        public Builder withTables(Map<String, TableData> snapshotTables) {
            this.tables = snapshotTables;
            return this;
        }

        /** Add one metric version. Default version is the latest-added version
         *  unless {@link #defaultVersion(String, int)} says otherwise. */
        public Builder addMetric(MetricDef def) {
            NavigableVersions v =
                    metrics.computeIfAbsent(def.name(), k -> new NavigableVersions());
            if (v.byVersion.put(def.version(), def) != null) {
                throw new InvalidDefinitionException(
                        "duplicate definition for '" + def.name() + "' v" + def.version());
            }
            if (def.version() > v.defaultVersion) {
                v.defaultVersion = def.version();
            }
            return this;
        }

        public Builder defaultVersion(String name, int version) {
            NavigableVersions v = metrics.get(name);
            if (v == null || !v.byVersion.containsKey(version)) {
                throw new UnknownVersionException(name, version);
            }
            v.defaultVersion = version;
            defaultsSet.add(name);
            return this;
        }

        public Builder addDimension(DerivedDimensionDef dim) {
            dims.computeIfAbsent(dim.table(), k -> new ArrayList<>()).add(dim);
            return this;
        }

        public Catalog build() {
            // --- 1. bind derived dimensions per table, in declaration order
            Map<String, List<DerivedDimensionDef>> boundDims = new LinkedHashMap<>();
            for (Map.Entry<String, List<DerivedDimensionDef>> e : dims.entrySet()) {
                String tableName = e.getKey();
                TableData table = tables.get(tableName);
                if (table == null) {
                    throw new InvalidDefinitionException(
                            "derived dimension references unknown table '" + tableName + "'");
                }
                Map<String, DataType> visible = new LinkedHashMap<>();
                for (FieldDef f : table.columns()) {
                    visible.put(f.name(), f.type());
                }
                List<DerivedDimensionDef> ordered = new ArrayList<>();
                for (DerivedDimensionDef dim : e.getValue()) {
                    String ctx = "derived dimension '" + dim.name() + "' on table '" + tableName + "'";
                    if (visible.containsKey(dim.name())) {
                        throw new InvalidDefinitionException(
                                "derived dimension '" + dim.name() + "' on table '" + tableName
                                        + "' collides with a physical column or an earlier dimension");
                    }
                    DataType declared = bindWithSelfCheck(dim.expr(), dim.name(), tableScope(visible), ctx);
                    visible.put(dim.name(), declared);
                    ordered.add(dim);
                }
                boundDims.put(tableName, List.copyOf(ordered));
            }

            // --- 2. validate references (unknown metric/version, self reference)
            //         and detect cycles before any formula type checking.
            for (NavigableVersions v : metrics.values()) {
                for (MetricDef def : v.byVersion.values()) {
                    if (def instanceof MetricDef.DerivedMetricDef d) {
                        Set<String> seenNames = new HashSet<>();
                        for (MetricRef ref : d.dependencies()) {
                            if (ref.metric().equals(d.name())) {
                                throw new SelfReferenceException(d.name() + " v" + d.version());
                            }
                            if (!seenNames.add(ref.metric())) {
                                throw new InvalidDefinitionException(
                                        "derived metric '" + d.name() + "' v" + d.version()
                                                + " lists dependency '" + ref.metric() + "' more than once");
                            }
                            lookup(ref.metric(), ref.version()); // throws if missing
                        }
                    }
                }
            }
            List<String> topo = topologicalOrder();

            // --- 3. bind base metrics, then derived formulas in topological order
            for (String node : topo) {
                MetricDef def = lookup(node);
                if (def instanceof MetricDef.BaseMetricDef b) {
                    bindBaseMetric(b, boundDims);
                } else {
                    bindDerivedFormula((MetricDef.DerivedMetricDef) def);
                }
            }

            return new Catalog(revision, copyMetrics(metrics), boundDims,
                    Map.copyOf(metricType), Map.copyOf(tables));
        }

        private MetricDef lookup(String name, int version) {
            NavigableVersions v = metrics.get(name);
            if (v == null) {
                throw new UnknownMetricException(name);
            }
            MetricDef def = v.byVersion.get(version);
            if (def == null) {
                throw new UnknownVersionException(name, version);
            }
            return def;
        }

        private MetricDef lookup(String node) {
            int at = node.lastIndexOf('#');
            return lookup(node.substring(0, at), Integer.parseInt(node.substring(at + 1)));
        }

        private void bindBaseMetric(MetricDef.BaseMetricDef b,
                                    Map<String, List<DerivedDimensionDef>> boundDims) {
            TableData table = tables.get(b.table());
            if (table == null) {
                throw new InvalidDefinitionException(
                        "base metric '" + b.name() + "' v" + b.version()
                                + " references unknown table '" + b.table() + "'");
            }
            Map<String, DataType> visible = new LinkedHashMap<>();
            table.columns().forEach(f -> visible.put(f.name(), f.type()));
            for (DerivedDimensionDef d : boundDims.getOrDefault(b.table(), List.of())) {
                DataType dt = d.expr().bind(tableScope(visible), "derived dimension '" + d.name() + "'");
                visible.put(d.name(), dt);
            }
            String ctx = "base metric '" + b.name() + "' v" + b.version();
            DataType inputType;
            if (b.countsRows()) {
                if (b.aggregation() != Aggregation.COUNT) {
                    throw new InvalidDefinitionException(
                            ctx + " aggregation " + b.aggregation() + " needs a measure expression");
                }
                inputType = null;
            } else {
                inputType = b.measure().bind(tableScope(visible), ctx + " measure");
            }
            DataType result = b.aggregation().resultType(inputType, b.countsRows());
            if (b.filter() != null) {
                DataType ft = b.filter().bind(tableScope(visible), ctx + " filter");
                if (ft != DataType.BOOLEAN) {
                    throw TypeMismatchException.expression(ctx + " filter", "BOOLEAN", ft.toString());
                }
            }
            metricType.put(b.name() + "#" + b.version(), result);
        }

        private void bindDerivedFormula(MetricDef.DerivedMetricDef d) {
            Map<String, DataType> depTypes = new LinkedHashMap<>();
            for (MetricRef ref : d.dependencies()) {
                depTypes.put(ref.metric(),
                        metricType.get(ref.metric() + "#" + ref.version()));
            }
            String ctx = "derived metric '" + d.name() + "' v" + d.version();
            Scope formulaScope = name -> {
                if (!depTypes.containsKey(name)) {
                    throw new UnknownFieldException(name, ctx + " formula");
                }
                return Optional.of(depTypes.get(name));
            };
            DataType result = d.formula().bind(formulaScope, ctx + " formula");
            metricType.put(d.name() + "#" + d.version(), result);
        }

        /** Postorder of every versioned metric node; edges = explicit dependencies. */
        private List<String> topologicalOrder() {
            List<String> order = new ArrayList<>();
            Map<String, Integer> color = new HashMap<>();
            List<String> path = new ArrayList<>();
            for (Map.Entry<String, NavigableVersions> e : metrics.entrySet()) {
                for (Integer ver : e.getValue().byVersion.navigableKeySet()) {
                    visit(e.getKey(), ver, color, path, order);
                }
            }
            return order;
        }

        private void visit(String name, int version, Map<String, Integer> color,
                           List<String> path, List<String> order) {
            String node = name + "#" + version;
            int state = color.getOrDefault(node, 0);
            if (state == 2) {
                return;
            }
            if (state == 1) {
                int start = path.indexOf(node);
                List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
                cycle.add(node);
                throw new CircularDependencyException(cycle.stream().map(Catalog::formatNode).toList());
            }
            path.add(node);
            color.put(node, 1);
            MetricDef def = metrics.get(name).byVersion.get(version);
            for (MetricRef ref : def.dependencies()) {
                visit(ref.metric(), ref.version(), color, path, order);
            }
            color.put(node, 2);
            path.remove(path.size() - 1);
            order.add(node);
        }

        private DataType bindWithSelfCheck(Expr expr, String selfName, Scope scope, String ctx) {
            Scope guarded = name -> {
                if (name.equals(selfName)) {
                    throw new SelfReferenceException(selfName);
                }
                return scope.typeOf(name);
            };
            return expr.bind(guarded, ctx);
        }

        private Map<String, NavigableVersions> copyMetrics(Map<String, NavigableVersions> src) {
            Map<String, NavigableVersions> out = new LinkedHashMap<>();
            for (Map.Entry<String, NavigableVersions> e : src.entrySet()) {
                NavigableVersions v = new NavigableVersions();
                v.byVersion = new java.util.TreeMap<>(e.getValue().byVersion);
                v.defaultVersion = e.getValue().defaultVersion;
                out.put(e.getKey(), v);
            }
            return Collections.unmodifiableMap(out);
        }
    }

    /** Versions of one metric name. */
    private static final class NavigableVersions {
        private java.util.NavigableMap<Integer, MetricDef> byVersion = new java.util.TreeMap<>();
        private int defaultVersion;
    }

    static String formatNode(String node) {
        int at = node.lastIndexOf('#');
        return node.substring(0, at) + "[v" + node.substring(at + 1) + "]";
    }
}
