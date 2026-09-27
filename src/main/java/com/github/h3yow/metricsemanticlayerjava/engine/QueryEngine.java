package com.github.h3yow.metricsemanticlayerjava.engine;

import com.github.h3yow.metricsemanticlayerjava.definition.Aggregation;
import com.github.h3yow.metricsemanticlayerjava.definition.Catalog;
import com.github.h3yow.metricsemanticlayerjava.definition.DerivedDimensionDef;
import com.github.h3yow.metricsemanticlayerjava.definition.MetricDef;
import com.github.h3yow.metricsemanticlayerjava.definition.MetricRef;
import com.github.h3yow.metricsemanticlayerjava.exception.QueryValidationException;
import com.github.h3yow.metricsemanticlayerjava.exception.UnknownFieldException;
import com.github.h3yow.metricsemanticlayerjava.expr.Scope;
import com.github.h3yow.metricsemanticlayerjava.model.DataType;
import com.github.h3yow.metricsemanticlayerjava.model.DataSnapshot;
import com.github.h3yow.metricsemanticlayerjava.model.Row;
import com.github.h3yow.metricsemanticlayerjava.model.TableData;
import com.github.h3yow.metricsemanticlayerjava.query.LineageNode;
import com.github.h3yow.metricsemanticlayerjava.query.Query;
import com.github.h3yow.metricsemanticlayerjava.query.QueryResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes {@link Query}s against one <em>fixed, immutable</em> catalog/snapshot
 * pair. Instances are created by the semantic layer when a new published
 * catalog or snapshot appears and are never mutated, so a query sees a single
 * consistent generation for its whole execution.
 *
 * <p>Base-metric group aggregations and derived-dimension materialisations are
 * memoized: repeated or concurrent queries over the same snapshot never rescan
 * the same table twice. Because the cache lives on an engine instance tied to
 * one generation, it can never serve a value from a stale snapshot.
 */
public final class QueryEngine {

    private final Catalog catalog;
    private final DataSnapshot snapshot;

    private final Map<String, List<Map<String, Object>>> enrichedRowsCache = new ConcurrentHashMap<>();
    private final Map<String, Map<List<Object>, Object>> aggregateCache = new ConcurrentHashMap<>();

    public QueryEngine(Catalog catalog, DataSnapshot snapshot) {
        this.catalog = catalog;
        this.snapshot = snapshot;
    }

    public Catalog catalog() {
        return catalog;
    }

    public DataSnapshot snapshot() {
        return snapshot;
    }

    // ---------------------------------------------------------------- lineage

    /** Resolve one metric (default version if null) and trace every dependency. */
    public LineageNode lineage(String name, Integer version) {
        MetricDef def = catalog.resolve(name, version);
        int v = def.version();
        List<LineageNode> deps = new ArrayList<>();
        for (MetricRef ref : def.dependencies()) {
            deps.add(lineage(ref.metric(), ref.version()));
        }
        String kind;
        String detail;
        if (def instanceof MetricDef.BaseMetricDef b) {
            kind = "BASE";
            String measure = b.countsRows() ? "*" : b.measure().describe();
            String filter = b.filter() == null ? "" : " FILTER " + b.filter().describe();
            detail = b.aggregation() + "(" + measure + ") OVER " + b.table() + filter;
        } else {
            MetricDef.DerivedMetricDef d = (MetricDef.DerivedMetricDef) def;
            kind = "DERIVED";
            detail = "FORMULA " + d.formula().describe();
        }
        return new LineageNode(name, v, kind, detail, List.copyOf(deps));
    }

    /** Human-readable execution plan, used by logs and the /explain endpoint. */
    public Map<String, Object> explain(Query query) {
        ResolvedPlan plan = resolve(query);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("snapshotId", snapshot.id());
        out.put("catalogRevision", catalog.revision());
        out.put("versions", plan.versions);
        out.put("groupBy", query.groupBy());
        List<String> scans = new ArrayList<>();
        for (String base : plan.baseScans) {
            MetricDef.BaseMetricDef b = (MetricDef.BaseMetricDef) resolveNode(base);
            scans.add(b.name() + "[v" + b.version() + "] -> " + b.table());
        }
        out.put("tableScans", scans);
        Map<String, LineageNode> lin = new LinkedHashMap<>();
        for (Query.MetricRequest m : query.metrics()) {
            lin.put(m.name(), lineage(m.name(), m.version()));
        }
        out.put("lineage", lin);
        return out;
    }

    // ---------------------------------------------------------------- execute

    public QueryResult execute(Query query) {
        ResolvedPlan plan = resolve(query);

        // Metric values per group for every resolved node (requested + transitive).
        Map<String, Map<List<Object>, Object>> valuesByNode = new HashMap<>();
        for (String node : plan.evaluationOrder) {
            valuesByNode.put(node, evaluateNode(node, query.groupBy()));
        }

        // Union of group keys preserving first-encounter order.
        List<List<Object>> groupKeys = new ArrayList<>();
        Set<List<Object>> seenKeys = new HashSet<>();
        if (query.groupBy().isEmpty()) {
            groupKeys.add(List.of());
            seenKeys.add(List.of());
        } else {
            for (Query.MetricRequest m : query.metrics()) {
                int v = plan.versions.get(m.name());
                collectKeys(m.name() + "#" + v, valuesByNode, groupKeys, seenKeys);
            }
        }

        // Output columns: dimensions first, then requested metrics.
        List<String> columns = new ArrayList<>(query.groupBy());
        for (Query.MetricRequest m : query.metrics()) {
            columns.add(m.name());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (List<Object> key : groupKeys) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < query.groupBy().size(); i++) {
                row.put(query.groupBy().get(i), key.get(i));
            }
            for (Query.MetricRequest m : query.metrics()) {
                int v = plan.versions.get(m.name());
                Map<List<Object>, Object> vals = valuesByNode.get(m.name() + "#" + v);
                row.put(m.name(), vals.get(key)); // null when no matching rows
            }
            rows.add(row);
        }

        // HAVING-style post-aggregation filter
        if (query.where() != null) {
            Scope outScope = name -> {
                int idx = columns.indexOf(name);
                if (idx < 0) {
                    return Optional.empty();
                }
                if (idx < query.groupBy().size()) {
                    return Optional.of(dimensionType(name, plan));
                }
                int mi = idx - query.groupBy().size();
                Query.MetricRequest m = query.metrics().get(mi);
                return Optional.of(catalog.resultTypeOf(m.name(), plan.versions.get(m.name())));
            };
            DataType wt = query.where().bind(outScope, "query WHERE/HAVING filter");
            if (wt != DataType.BOOLEAN) {
                throw com.github.h3yow.metricsemanticlayerjava.exception.TypeMismatchException
                        .expression("query filter", "BOOLEAN", wt.toString());
            }
            rows.removeIf(r -> !Boolean.TRUE.equals(query.where().eval(r)));
        }

        // ORDER BY
        if (!query.orderBy().isEmpty()) {
            List<Comparator<Map<String, Object>>> comps = new ArrayList<>();
            for (Query.OrderTerm term : query.orderBy()) {
                if (!columns.contains(term.column())) {
                    throw new QueryValidationException(
                            "ORDER BY column '" + term.column() + "' is not in the query output: " + columns);
                }
                Comparator<Map<String, Object>> cmp = (a, b) -> compareValues(
                        a.get(term.column()), b.get(term.column()));
                if (term.direction() == com.github.h3yow.metricsemanticlayerjava.query.SortOrder.DESC) {
                    cmp = cmp.reversed();
                }
                comps.add(cmp);
            }
            rows.sort(comps.stream().reduce(Comparator::thenComparing).orElseThrow());
        }

        // LIMIT
        Integer limit = query.limit();
        if (limit != null && limit > 0 && rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }

        Map<String, LineageNode> lin = new LinkedHashMap<>();
        for (Query.MetricRequest m : query.metrics()) {
            lin.put(m.name(), lineage(m.name(), m.version()));
        }

        return new QueryResult(List.copyOf(columns), List.copyOf(rows),
                snapshot.id(), catalog.revision(), new LinkedHashMap<>(plan.versions),
                lin, System.currentTimeMillis());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1; // nulls last regardless of direction
        }
        if (b == null) {
            return -1;
        }
        if (a instanceof Number x && b instanceof Number y
                && !(a instanceof Long && b instanceof Long)) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        return ((Comparable) a).compareTo(b);
    }

    // ---------------------------------------------------------------- plan

    private ResolvedPlan resolve(Query query) {
        Set<String> requested = new HashSet<>();
        for (Query.MetricRequest m : query.metrics()) {
            if (!requested.add(m.name())) {
                throw new QueryValidationException(
                        "metric '" + m.name() + "' is requested twice in one query");
            }
        }

        Map<String, Integer> versions = new LinkedHashMap<>();
        List<String> evalOrder = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> baseScans = new java.util.LinkedHashSet<>();
        for (Query.MetricRequest m : query.metrics()) {
            MetricDef def = catalog.resolve(m.name(), m.version());
            planVisit(def.name(), def.version(), versions, evalOrder, visited, baseScans, m.name());
        }

        // Validate group-by dimensions are visible on every scanned base table.
        Set<String> dimensionTables = new HashSet<>();
        for (String base : baseScans) {
            MetricDef.BaseMetricDef b = (MetricDef.BaseMetricDef) resolveNode(base);
            for (String dim : query.groupBy()) {
                if (dimensionTypeOnTable(dim, b.table()) == null) {
                    throw new UnknownFieldException(dim,
                            "GROUP BY against base metric '" + b.name() + "' on table '" + b.table() + "'");
                }
            }
            dimensionTables.add(b.table());
        }

        return new ResolvedPlan(versions, evalOrder, baseScans, dimensionTables);
    }

    private void planVisit(String name, int version,
                           Map<String, Integer> versions,
                           List<String> evalOrder, Set<String> visited,
                           Set<String> baseScans, String requestedName) {
        String node = name + "#" + version;
        versions.put(requestedName, version);
        if (!visited.add(node)) {
            return;
        }
        MetricDef def = catalog.resolve(name, version);
        for (MetricRef ref : def.dependencies()) {
            planVisit(ref.metric(), ref.version(), versions, evalOrder, visited, baseScans, ref.metric());
        }
        evalOrder.add(node);
        if (def instanceof MetricDef.BaseMetricDef) {
            baseScans.add(node);
        }
    }

    // ---------------------------------------------------------------- evaluation

    private Map<List<Object>, Object> evaluateNode(String node, List<String> groupBy) {
        MetricDef def = resolveNode(node);
        if (def instanceof MetricDef.BaseMetricDef b) {
            return aggregate(b, groupBy);
        }
        MetricDef.DerivedMetricDef d = (MetricDef.DerivedMetricDef) def;
        Map<String, Map<List<Object>, Object>> depValues = new LinkedHashMap<>();
        for (MetricRef ref : d.dependencies()) {
            depValues.put(ref.metric(), evaluateNode(ref.metric() + "#" + ref.version(), groupBy));
        }
        Map<List<Object>, Object> out = new LinkedHashMap<>();
        Set<List<Object>> keys = new LinkedHashSetWithListKeys();
        depValues.values().forEach(m -> keys.addAll(m.keySet()));
        if (groupBy.isEmpty()) {
            keys.add(List.of());
        }
        for (List<Object> key : keys) {
            Map<String, Object> formulaRow = new HashMap<>();
            for (Map.Entry<String, Map<List<Object>, Object>> e : depValues.entrySet()) {
                formulaRow.put(e.getKey(), e.getValue().get(key));
            }
            out.put(key, d.formula().eval(formulaRow));
        }
        return out;
    }

    private Map<List<Object>, Object> aggregate(MetricDef.BaseMetricDef b, List<String> groupBy) {
        String cacheKey = b.name() + "#" + b.version() + "|" + String.join(",", groupBy);
        return aggregateCache.computeIfAbsent(cacheKey, k -> computeAggregate(b, groupBy));
    }

    private Map<List<Object>, Object> computeAggregate(MetricDef.BaseMetricDef b, List<String> groupBy) {
        List<Map<String, Object>> rows = enrichedRows(b.table());
        Map<List<Object>, Accumulator> accs = new LinkedHashMap<>();
        if (groupBy.isEmpty()) {
            accs.put(List.of(), newAccumulator(b.aggregation()));
        }
        for (Map<String, Object> row : rows) {
            if (b.filter() != null && !Boolean.TRUE.equals(b.filter().eval(row))) {
                continue;
            }
            List<Object> key = new ArrayList<>(groupBy.size());
            for (String dim : groupBy) {
                key.add(row.get(dim));
            }
            accs.computeIfAbsent(key, k -> newAccumulator(b.aggregation()))
                    .add(b.countsRows() ? null : b.measure().eval(row));
        }
        Map<List<Object>, Object> result = new LinkedHashMap<>();
        accs.forEach((key, acc) -> result.put(key, acc.finish()));
        return result;
    }

    private static Accumulator newAccumulator(Aggregation agg) {
        return switch (agg) {
            case SUM -> new SumAcc();
            case COUNT -> new CountAcc();
            case COUNT_DISTINCT -> new CountDistinctAcc();
            case AVG -> new AvgAcc();
            case MIN -> new MinMaxAcc(true);
            case MAX -> new MinMaxAcc(false);
        };
    }

    /** Physical + derived columns for every row of a table, computed once. */
    private List<Map<String, Object>> enrichedRows(String tableName) {
        return enrichedRowsCache.computeIfAbsent(tableName, t -> {
            TableData table = snapshot.table(t);
            List<DerivedDimensionDef> dims = catalog.dimensionsOf(t);
            List<Map<String, Object>> out = new ArrayList<>(table.rows().size());
            for (Row row : table.rows()) {
                Map<String, Object> v = new LinkedHashMap<>(row.values());
                for (DerivedDimensionDef dim : dims) {
                    v.put(dim.name(), dim.expr().eval(v));
                }
                out.add(Collections.unmodifiableMap(v));
            }
            return List.copyOf(out);
        });
    }

    private void collectKeys(String node,
                             Map<String, Map<List<Object>, Object>> values,
                             List<List<Object>> out, Set<List<Object>> seen) {
        Map<List<Object>, Object> vals = values.get(node);
        for (List<Object> key : vals.keySet()) {
            if (seen.add(key)) {
                out.add(key);
            }
        }
    }

    private DataType dimensionType(String dim, ResolvedPlan plan) {
        DataType t = null;
        for (String table : plan.dimensionTables) {
            DataType onTable = dimensionTypeOnTable(dim, table);
            if (onTable == null) {
                throw new UnknownFieldException(dim, "query output on table '" + table + "'");
            }
            if (t == null) {
                t = onTable;
            } else if (t != onTable) {
                throw new QueryValidationException(
                        "dimension '" + dim + "' has different types across tables: " + t + " vs " + onTable);
            }
        }
        return t;
    }

    private final Map<String, Map<String, DataType>> tableScopeTypes = new ConcurrentHashMap<>();

    /** Type of a physical column or derived dimension on a table, or null if absent. */
    private DataType dimensionTypeOnTable(String dim, String table) {
        return tableScopeTypes.computeIfAbsent(table, this::buildTableScope).get(dim);
    }

    private Map<String, DataType> buildTableScope(String table) {
        TableData t = snapshot.table(table);
        Map<String, DataType> visible = new LinkedHashMap<>();
        t.columns().forEach(c -> visible.put(c.name(), c.type()));
        for (DerivedDimensionDef d : catalog.dimensionsOf(table)) {
            DataType dt = d.expr().bind(n -> Optional.ofNullable(visible.get(n)),
                    "derived dimension '" + d.name() + "'");
            visible.put(d.name(), dt);
        }
        return Collections.unmodifiableMap(visible);
    }

    private MetricDef resolveNode(String node) {
        MetricRef k = baseKey(node);
        return catalog.resolve(k.metric(), k.version());
    }

    private static MetricRef baseKey(String node) {
        int at = node.lastIndexOf('#');
        return new MetricRef(node.substring(0, at), Integer.parseInt(node.substring(at + 1)));
    }

    // ---------------------------------------------------------------- accumulators

    private interface Accumulator {
        void add(Object value);

        Object finish();
    }

    private static final class SumAcc implements Accumulator {
        boolean asDouble;
        long longSum;
        double doubleSum;

        @Override
        public void add(Object v) {
            if (v == null) {
                return;
            }
            if (v instanceof Long l) {
                if (asDouble) {
                    doubleSum += l;
                } else {
                    longSum += l;
                }
            } else {
                if (!asDouble) {
                    doubleSum = longSum;
                    asDouble = true;
                }
                doubleSum += ((Number) v).doubleValue();
            }
        }

        @Override
        public Object finish() {
            return asDouble ? doubleSum : longSum;
        }
    }

    private static final class CountAcc implements Accumulator {
        long n;

        @Override
        public void add(Object v) {
            n++;
        }

        @Override
        public Object finish() {
            return n;
        }
    }

    private static final class CountDistinctAcc implements Accumulator {
        final Set<Object> values = new HashSet<>();

        @Override
        public void add(Object v) {
            if (v != null) {
                values.add(v);
            }
        }

        @Override
        public Object finish() {
            return (long) values.size();
        }
    }

    private static final class AvgAcc implements Accumulator {
        double sum;
        long n;

        @Override
        public void add(Object v) {
            if (v != null) {
                sum += ((Number) v).doubleValue();
                n++;
            }
        }

        @Override
        public Object finish() {
            return n == 0 ? null : sum / n;
        }
    }

    private static final class MinMaxAcc implements Accumulator {
        final boolean min;
        Comparable<Object> best;

        @SuppressWarnings("unchecked")
        MinMaxAcc(boolean min) {
            this.min = min;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void add(Object v) {
            if (v == null) {
                return;
            }
            if (best == null) {
                best = (Comparable<Object>) v;
                return;
            }
            int cmp = best.compareTo(v);
            if ((min && cmp > 0) || (!min && cmp < 0)) {
                best = (Comparable<Object>) v;
            }
        }

        @Override
        public Object finish() {
            return best;
        }
    }

    /** Key set that compares List keys by content (ArrayList already does) but keeps encounter order. */
    private static final class LinkedHashSetWithListKeys extends java.util.LinkedHashSet<List<Object>> {
    }

    private record ResolvedPlan(Map<String, Integer> versions,
                                List<String> evaluationOrder,
                                Set<String> baseScans,
                                Set<String> dimensionTables) {
    }
}
