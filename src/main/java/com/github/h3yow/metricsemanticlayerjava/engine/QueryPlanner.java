package com.github.h3yow.metricsemanticlayerjava.engine;

import com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException;
import com.github.h3yow.metricsemanticlayerjava.error.UnknownFieldException;
import com.github.h3yow.metricsemanticlayerjava.expr.Expr;
import com.github.h3yow.metricsemanticlayerjava.expr.ExpressionParser;
import com.github.h3yow.metricsemanticlayerjava.model.Column;
import com.github.h3yow.metricsemanticlayerjava.model.DataSnapshot;
import com.github.h3yow.metricsemanticlayerjava.model.DataType;
import com.github.h3yow.metricsemanticlayerjava.model.DerivedDimension;
import com.github.h3yow.metricsemanticlayerjava.model.LocalTable;
import com.github.h3yow.metricsemanticlayerjava.model.MetricDefinition;
import com.github.h3yow.metricsemanticlayerjava.model.QueryRequest;
import com.github.h3yow.metricsemanticlayerjava.model.QueryResult;
import com.github.h3yow.metricsemanticlayerjava.model.SortOrder;
import com.github.h3yow.metricsemanticlayerjava.registry.MetricRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 查询改写 / 执行器：把声明式指标查询改写为本地表上的分组聚合计算。
 *
 * <p>执行步骤：解析版本 → 校验维度/过滤 → 派生维度投影 → 过滤+分组 →
 * 递归求值指标（复合指标按固定版本引用依赖，绝不跨版本）→ having → 排序 → limit。</p>
 */
public final class QueryPlanner {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanner.class);

    private final MetricRegistry registry;
    private final RowEvaluator evaluator;
    private final ExpressionParser parser = new ExpressionParser();
    private final DefinitionValidator validator = new DefinitionValidator();

    public QueryPlanner(MetricRegistry registry, RowEvaluator evaluator) {
        this.registry = registry;
        this.evaluator = evaluator;
    }

    /** 单次查询的可变执行上下文。 */
    private final class Ctx {
        final QueryRequest request;
        final MetricRegistry.RegistrySnapshot snap;
        final DataSnapshot data;
        final LocalTable table;
        final MetricDefinition root;
        final List<DimensionProjection> dims;
        final List<Expr> queryFilters;
        /** 分组：分配稳定 groupId，保证递归求值的记忆化键可靠。 */
        final Map<List<Object>, Group> groups = new LinkedHashMap<>();
        /** memo 里只放 {@link Done}（已完成）；在算状态由 inFlight 表达。 */
        final Map<String, Done> memo = new java.util.HashMap<>();
        /** 正在求值的坐标，用于把环检测落到具体查询。 */
        final java.util.Set<String> inFlight = new java.util.HashSet<>();
        final LinkedHashSet<String> chain = new LinkedHashSet<>();

        Ctx(QueryRequest request, MetricRegistry.RegistrySnapshot snap, DataSnapshot data,
            LocalTable table, MetricDefinition root, List<DimensionProjection> dims,
            List<Expr> queryFilters) {
            this.request = request;
            this.snap = snap;
            this.data = data;
            this.table = table;
            this.root = root;
            this.dims = dims;
            this.queryFilters = queryFilters;
        }
    }

    /** 一个分组：稳定 id、维度键、成员行。 */
    private record Group(int id, List<Object> key, List<Map<String, Object>> rows) {
    }

    /** memo 完成标记：区分"在算"与"已算（含 null 结果）"。 */
    private record Done(Object value) {
    }

    /** 分组维度投影：名字 + 行级取值表达式（物理列或派生维度）。 */
    private record DimensionProjection(String name, Expr expression, DataType type) {
    }

    public QueryResult execute(QueryRequest request,
                               MetricRegistry.RegistrySnapshot registrySnapshot,
                               DataSnapshot dataSnapshot) {
        long start = System.nanoTime();

        // 1. 版本解析
        String metricName = request.metricName();
        int version = request.metricVersion() != null
                ? request.metricVersion()
                : registry.defaultVersionOf(registrySnapshot, metricName);
        MetricDefinition root = registry.requireVersion(registrySnapshot, metricName, version);

        // 2. 表
        LocalTable table = dataSnapshot.table(root.table());
        if (table == null) {
            throw new InvalidDefinitionException("数据快照 " + dataSnapshot.id()
                    + " 中不存在表 " + root.table());
        }
        if (request.table() != null && !request.table().equals(table.name())) {
            throw new InvalidDefinitionException("查询声明的表 " + request.table()
                    + " 与指标定义的表 " + table.name() + " 不一致");
        }

        // 3. 维度解析（物理列 / 派生维度），保持请求顺序
        List<DimensionProjection> dims = new ArrayList<>();
        Map<String, DataType> availableDimTypes = new LinkedHashMap<>();
        for (Column c : table.columns()) {
            availableDimTypes.put(c.name(), c.type());
        }
        for (String dimName : request.dimensions()) {
            DimensionProjection dp = resolveDimension(dimName, table, registrySnapshot);
            dims.add(dp);
            availableDimTypes.put(dimName, dp.type());
        }

        // 4. 查询级过滤表达式
        List<Expr> queryFilters = new ArrayList<>();
        for (String f : request.filters()) {
            queryFilters.add(validator.validateQueryFilter(f, table, availableDimTypes));
        }

        Ctx ctx = new Ctx(request, registrySnapshot, dataSnapshot, table, root, dims, queryFilters);

        // 5. 投影派生维度 + 过滤 + 分组
        projectFilterAndGroup(ctx);

        // 6. 递归求值
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Group group : ctx.groups.values()) {
            List<Object> key = group.key();
            Object value = evalMetric(ctx, root, group, true);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < dims.size(); i++) {
                row.put(dims.get(i).name(), key.get(i));
            }
            row.put(metricName, value);
            rows.add(row);
        }
        // 无分组维度时，即使没有数据行也要给出一个空组
        if (dims.isEmpty() && rows.isEmpty()) {
            Group empty = new Group(0, List.of(), List.of());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(metricName, evalMetric(ctx, root, empty, true));
            rows.add(row);
        }

        // 7. having（聚合后过滤）
        if (request.having() != null && !request.having().isBlank()) {
            Expr having = parser.parse(request.having());
            rejectMetricRefs(having, "having");
            for (String col : ExpressionParser.referencedColumns(having)) {
                if (!col.equals(metricName) && availableDimTypes.get(col) == null) {
                    throw new UnknownFieldException(col, "having 作用域");
                }
            }
            DataType ht = evaluator.inferType(having, c -> {
                if (c.equals(metricName)) {
                    return DataType.DOUBLE;
                }
                return availableDimTypes.get(c);
            });
            if (ht != DataType.BOOLEAN) {
                throw new com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException(
                        request.having(), DataType.BOOLEAN, ht.name());
            }
            rows.removeIf(r -> !evaluator.evalBoolean(having, r));
        }

        // 8. 排序
        if (request.orderBy() != null && !request.orderBy().isBlank()) {
            String orderBy = request.orderBy();
            if (!orderBy.equals(metricName) && availableDimTypes.get(orderBy) == null) {
                throw new UnknownFieldException(orderBy, "order by 作用域");
            }
            SortOrder dir = request.sortOrder() == null ? SortOrder.ASC : request.sortOrder();
            rows.sort((a, b) -> compareForOrder(a.get(orderBy), b.get(orderBy), dir));
        }

        // 9. limit
        if (request.limit() > 0 && rows.size() > request.limit()) {
            rows = List.copyOf(rows.subList(0, request.limit()));
        }

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        List<String> dimNames = dims.stream().map(DimensionProjection::name).toList();
        String rationale = buildRationale(request, version, queryFilters.size(), ctx);

        log.info("查询执行 metric={} 命中版本=v{} snapshot={} 维度={} 过滤数={} 分组数={} 依赖链={} 依据={}",
                metricName, version, dataSnapshot.id(), dimNames, queryFilters.size(),
                ctx.groups.size(), ctx.chain, rationale);

        return new QueryResult(metricName, version, dataSnapshot.id(), dimNames,
                List.copyOf(rows), List.copyOf(ctx.chain), rationale, false, elapsedMillis);
    }

    // ------------------------------------------------------------------
    // projection / grouping
    // ------------------------------------------------------------------

    private DimensionProjection resolveDimension(String name, LocalTable table,
                                                 MetricRegistry.RegistrySnapshot snap) {
        Column physical = table.column(name);
        if (physical != null) {
            return new DimensionProjection(name, new Expr.ColumnRef(name), physical.type());
        }
        DerivedDimension dim = snap.dimensions().get(name);
        if (dim == null) {
            throw new UnknownFieldException(name, "表 " + table.name() + " 的分组维度");
        }
        if (!dim.table().equals(table.name())) {
            throw new InvalidDefinitionException("派生维度 " + name + " 属于表 " + dim.table()
                    + "，不能用于表 " + table.name());
        }
        Expr e = parser.parse(dim.expression());
        return new DimensionProjection(name, e, dim.resultType());
    }

    private void projectFilterAndGroup(Ctx ctx) {
        List<Map<String, Object>> enriched = new ArrayList<>(ctx.table.rows().size());
        for (Map<String, Object> raw : ctx.table.rows()) {
            Map<String, Object> row = new LinkedHashMap<>(raw);
            // 派生维度投影（同一派生维度在多处复用时只算一次）
            for (DimensionProjection dp : ctx.dims) {
                if (ctx.table.column(dp.name()) == null) {
                    row.put(dp.name(), evaluator.eval(dp.expression(), row));
                }
            }
            // 查询级过滤
            boolean pass = true;
            for (Expr f : ctx.queryFilters) {
                if (!evaluator.evalBoolean(f, row)) {
                    pass = false;
                    break;
                }
            }
            if (pass) {
                enriched.add(row);
            }
        }
        // 分组：List<Object> 键的 equals/hashCode 天然按内容比较
        int[] seq = {0};
        for (Map<String, Object> row : enriched) {
            List<Object> key = new ArrayList<>(ctx.dims.size());
            for (DimensionProjection dp : ctx.dims) {
                key.add(row.get(dp.name()));
            }
            Group existing = ctx.groups.get(key);
            if (existing == null) {
                existing = new Group(seq[0]++, key, new ArrayList<>());
                ctx.groups.put(key, existing);
            }
            existing.rows().add(row);
        }
    }

    // ------------------------------------------------------------------
    // metric evaluation (recursive, version-pinned)
    // ------------------------------------------------------------------

    /**
     * 求一个指标在某个组上的值。
     *
     * @param applyQueryFilters 是否叠加本次查询级过滤（仅原子指标生效；
     *                          复合指标不加，因为其原子依赖已经带过滤）
     */
    private Object evalMetric(Ctx ctx, MetricDefinition def, Group group,
                              boolean applyQueryFilters) {
        String coord = def.name() + "@v" + def.version();
        String memoKey = coord + (applyQueryFilters ? "|q" : "|d") + "|g" + group.id();
        synchronized (ctx.memo) {
            Done cached = ctx.memo.get(memoKey);
            if (cached != null) {
                return cached.value();
            }
            if (!ctx.inFlight.add(memoKey)) {
                throw new com.github.h3yow.metricsemanticlayerjava.error.CircularDependencyException(
                        List.of(coord, coord));
            }
        }
        try {
            ctx.chain.add(coord);            boolean hasExpression = def.expression() != null && !def.expression().isBlank();
            Expr ast = hasExpression ? parser.parse(def.expression()) : null;
            List<Expr.MetricRef> refs = ast == null ? List.of()
                    : ExpressionParser.referencedMetrics(ast);
            boolean composite = !refs.isEmpty();

            Object result;
            if (composite) {
                // 复合指标：先校验所有依赖的同表与版本存在；具体值在 evaluator.eval 中按需递归
                for (Expr.MetricRef ref : refs) {
                    MetricDefinition depDef = registry.requireVersion(ctx.snap,
                            ref.metricName(), ref.version());
                    if (!depDef.table().equals(def.table())) {
                        throw new InvalidDefinitionException("复合指标 " + def.name()
                                + " 依赖了不同表的指标 " + ref.metricName() + "@v" + ref.version());
                    }
                }
                result = evaluator.eval(ast,
                        col -> {
                            throw new InvalidDefinitionException("复合指标表达式出现列引用: " + col);
                        },
                        m -> {
                            MetricDefinition d = registry.requireVersion(ctx.snap,
                                    m.metricName(), m.version());
                            return evalMetric(ctx, d, group, applyQueryFilters);
                        });
            } else {
                result = evalAtomic(ctx, def, ast, group.rows(), applyQueryFilters);
            }
            synchronized (ctx.memo) {
                ctx.memo.put(memoKey, new Done(result));
            }
            return result;
        } finally {
            synchronized (ctx.memo) {
                ctx.inFlight.remove(memoKey);
            }
        }
    }

    private Object evalAtomic(Ctx ctx, MetricDefinition def, Expr rowExpression,
                              List<Map<String, Object>> rows, boolean applyQueryFilters) {
        Expr defFilter = (def.filterExpression() == null || def.filterExpression().isBlank())
                ? null : parser.parse(def.filterExpression());

        List<Object> measures = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (defFilter != null && !evaluator.evalBoolean(defFilter, row)) {
                continue;
            }
            Object measure;
            if (rowExpression != null) {
                measure = evaluator.eval(rowExpression, row);
            } else if (def.agg() == com.github.h3yow.metricsemanticlayerjava.model.AggType.COUNT
                    && (def.sourceColumn() == null || def.sourceColumn().isBlank())) {
                // COUNT(*) 语义：每行计 1
                measure = 1L;
            } else {
                measure = row.get(def.sourceColumn());
            }
            if (measure != null) {
                measures.add(measure);
            }
        }
        return Aggregator.aggregate(def.agg(), measures);
    }

    // ------------------------------------------------------------------
    // having / order / rationale
    // ------------------------------------------------------------------

    private void rejectMetricRefs(Expr e, String where) {
        if (!ExpressionParser.referencedMetrics(e).isEmpty()) {
            throw new InvalidDefinitionException(where + " 表达式不能引用其它指标");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private int compareForOrder(Object a, Object b, SortOrder dir) {
        int cmp;
        if (a == null && b == null) {
            cmp = 0;
        } else if (a == null) {
            cmp = -1;
        } else if (b == null) {
            cmp = 1;
        } else if (a instanceof Number na && b instanceof Number nb) {
            cmp = Double.compare(na.doubleValue(), nb.doubleValue());
        } else if (a instanceof Comparable && a.getClass().equals(b.getClass())) {
            cmp = ((Comparable) a).compareTo(b);
        } else {
            cmp = a.toString().compareTo(b.toString());
        }
        return dir == SortOrder.DESC ? -cmp : cmp;
    }

    private String buildRationale(QueryRequest req, int version, int filterCount, Ctx ctx) {
        List<String> parts = new ArrayList<>();
        parts.add(req.metricVersion() == null
                ? "未指定版本，采用注册表当前默认 v" + version
                : "请求显式指定 v" + version);
        parts.add("数据快照=" + ctx.data.id());
        parts.add("查询级过滤=" + filterCount + " 条");
        if (req.dimensions().isEmpty()) {
            parts.add("全表聚合（无分组维度）");
        }
        if (req.having() != null) {
            parts.add("having 后过滤");
        }
        if (req.orderBy() != null) {
            parts.add("按 " + req.orderBy() + " " + req.sortOrder() + " 排序");
        }
        return String.join("；", parts);
    }
}
