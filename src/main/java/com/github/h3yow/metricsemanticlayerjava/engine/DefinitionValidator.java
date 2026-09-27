package com.github.h3yow.metricsemanticlayerjava.engine;

import com.github.h3yow.metricsemanticlayerjava.error.CircularDependencyException;
import com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException;
import com.github.h3yow.metricsemanticlayerjava.error.MetricNotFoundException;
import com.github.h3yow.metricsemanticlayerjava.error.SelfReferenceException;
import com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException;
import com.github.h3yow.metricsemanticlayerjava.error.UnknownFieldException;
import com.github.h3yow.metricsemanticlayerjava.expr.Expr;
import com.github.h3yow.metricsemanticlayerjava.expr.ExpressionParser;
import com.github.h3yow.metricsemanticlayerjava.model.AggType;
import com.github.h3yow.metricsemanticlayerjava.model.Column;
import com.github.h3yow.metricsemanticlayerjava.model.DataSnapshot;
import com.github.h3yow.metricsemanticlayerjava.model.DataType;
import com.github.h3yow.metricsemanticlayerjava.model.DerivedDimension;
import com.github.h3yow.metricsemanticlayerjava.model.LocalTable;
import com.github.h3yow.metricsemanticlayerjava.model.MetricDefinition;
import com.github.h3yow.metricsemanticlayerjava.registry.MetricRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 定义校验器：所有畸形定义都在这里被拒绝，绝不静默按空值 / 零处理。
 *
 * <p>可区分的失败原因：</p>
 * <ul>
 *   <li>{@link UnknownFieldException} 未知字段（含函数误写、查询用了未注册维度）；</li>
 *   <li>{@link TypeMismatchException} 表达式类型不匹配 / 聚合源列类型不符 / 过滤非布尔；</li>
 *   <li>{@link SelfReferenceException} 指标直接自引用；</li>
 *   <li>{@link CircularDependencyException} 指标依赖成环（附环路）；</li>
 *   <li>{@link InvalidDefinitionException} 结构非法（源列缺失、维度引用指标等）。</li>
 * </ul>
 */
public final class DefinitionValidator {

    private final ExpressionParser parser = new ExpressionParser();
    private final RowEvaluator evaluator = new RowEvaluator();

    /** 解析并缓存表达式，语法错误在此以 MalformedExpressionException 拒绝。 */
    private Expr parse(String expression) {
        return parser.parse(expression);
    }

    /** 校验单个指标定义（自包含部分；跨指标环在 {@link #validateDependencyGraph} 校验）。 */
    public void validateMetric(MetricDefinition def, DataSnapshot snapshot) {
        LocalTable table = requireTable(def.table(), snapshot);
        boolean hasExpression = def.expression() != null && !def.expression().isBlank();
        Expr exprAst = hasExpression ? parse(def.expression()) : null;
        List<Expr.MetricRef> metricRefs = exprAst == null ? List.of()
                : ExpressionParser.referencedMetrics(exprAst);
        List<String> exprColumns = exprAst == null ? List.of()
                : ExpressionParser.referencedColumns(exprAst);
        boolean isRowDerived = hasExpression && metricRefs.isEmpty() && !exprColumns.isEmpty();

        // 1. 源列检查：COUNT 或带行级派生表达式时可省略源列
        if (def.agg() != AggType.COUNT && !isRowDerived) {
            if (def.sourceColumn() == null || def.sourceColumn().isBlank()) {
                throw new InvalidDefinitionException(
                        "指标 " + def.name() + "@v" + def.version()
                                + " 既无源列也无行级派生表达式，" + def.agg() + " 无法聚合");
            }
        }
        if (def.sourceColumn() != null && !def.sourceColumn().isBlank()) {
            Column src = table.column(def.sourceColumn());
            if (src == null) {
                throw new UnknownFieldException(def.sourceColumn(), "表 " + table.name());
            }
            switch (def.agg()) {
                case SUM, AVG -> {
                    if (!src.type().isNumeric()) {
                        throw new TypeMismatchException("源列 " + src.name(),
                                DataType.DOUBLE, src.type().name());
                    }
                }
                case MIN, MAX -> {
                    if (src.type() != DataType.STRING && !src.type().isNumeric()) {
                        throw new TypeMismatchException("源列 " + src.name(),
                                DataType.DOUBLE, src.type().name());
                    }
                }
                default -> { }
            }
        }

        // 2. 指标表达式：纯指标运算（复合指标）或行级派生（聚合前计算），二者不可混用
        if (hasExpression) {
            if (!metricRefs.isEmpty() && !exprColumns.isEmpty()) {
                throw new InvalidDefinitionException(
                        "指标表达式不能同时引用源表列与其它指标: " + def.expression());
            }
            if (!metricRefs.isEmpty()) {
                if (def.agg() != AggType.COUNT || def.sourceColumn() != null) {
                    throw new InvalidDefinitionException(
                            "复合指标（依赖其它指标）只能声明 agg=COUNT 且不指定源列: " + def.name());
                }
            } else if (!exprColumns.isEmpty()) {
                for (String c : exprColumns) {
                    if (table.column(c) == null) {
                        throw new UnknownFieldException(c, "表 " + table.name());
                    }
                }
                DataType measureType = evaluator.inferType(exprAst, c -> {
                    Column col = table.column(c);
                    return col == null ? null : col.type();
                });
                if (!measureType.isNumeric()) {
                    throw new TypeMismatchException(def.expression(), DataType.DOUBLE, measureType.name());
                }
            }
        }

        // 3. 自引用
        for (Expr.MetricRef m : metricRefs) {
            if (m.metricName().equals(def.name()) && m.version() == def.version()) {
                throw new SelfReferenceException(def.name() + "@v" + def.version());
            }
        }

        // 4. 过滤表达式：必须布尔、只能引用本表列、不得引用指标
        validateRowFilter(def.filterExpression(), table);
    }

    /** 校验派生维度：只能引用本表列，不得引用指标，声明结果类型必须与推断一致。 */
    public void validateDimension(DerivedDimension dim, DataSnapshot snapshot) {
        LocalTable table = requireTable(dim.table(), snapshot);
        if (table.column(dim.name()) != null) {
            throw new InvalidDefinitionException(
                    "派生维度名与物理列重名: " + dim.name() + "（表 " + table.name() + "）");
        }
        Expr e = parse(dim.expression());
        if (!ExpressionParser.referencedMetrics(e).isEmpty()) {
            throw new InvalidDefinitionException("派生维度不能引用指标: " + dim.name());
        }
        for (String c : ExpressionParser.referencedColumns(e)) {
            if (table.column(c) == null) {
                throw new UnknownFieldException(c, "表 " + table.name());
            }
        }
        DataType inferred = evaluator.inferType(e, c -> {
            Column col = table.column(c);
            return col == null ? null : col.type();
        });
        if (inferred != dim.resultType()) {
            throw new TypeMismatchException(dim.expression(), dim.resultType(), inferred.name());
        }
    }

    /**
     * 校验注册表中全部指标依赖闭包：
     * 引用的指标版本必须存在；不允许自引用；不允许环。
     */
    public void validateDependencyGraph(MetricRegistry registry, DataSnapshot snapshot) {
        MetricRegistry.RegistrySnapshot snap = registry.currentSnapshot();
        Map<String, List<Expr.MetricRef>> edges = new HashMap<>();

        for (MetricDefinition def : snap.metrics().values()) {
            if (def.expression() == null || def.expression().isBlank()) {
                edges.putIfAbsent(key(def), List.of());
                continue;
            }
            Expr e = parse(def.expression());
            List<Expr.MetricRef> refs = ExpressionParser.referencedMetrics(e);
            for (Expr.MetricRef ref : refs) {
                MetricDefinition target = snap.metric(ref.metricName(), ref.version());
                if (target == null) {
                    if (snap.hasMetricName(ref.metricName())) {
                        throw new com.github.h3yow.metricsemanticlayerjava.error.VersionNotFoundException(
                                ref.metricName(), ref.version());
                    }
                    throw new MetricNotFoundException(ref.metricName() + "@v" + ref.version());
                }
                if (target.name().equals(def.name()) && target.version() == def.version()) {
                    throw new SelfReferenceException(def.name() + "@v" + def.version());
                }
            }
            edges.put(key(def), refs);
        }

        // 环检测：带路径的 DFS
        Set<String> done = new HashSet<>();
        for (String node : new ArrayList<>(edges.keySet())) {
            List<String> path = new ArrayList<>();
            Set<String> onPath = new LinkedHashSet<>();
            dfsCycle(node, edges, done, onPath, path);
        }
    }

    private void dfsCycle(String node,
                          Map<String, List<Expr.MetricRef>> edges,
                          Set<String> done,
                          Set<String> onPath,
                          List<String> path) {
        if (done.contains(node)) {
            return;
        }
        if (onPath.contains(node)) {
            int start = path.indexOf(node);
            List<String> cycle = new ArrayList<>(path.subList(start, path.size()));
            cycle.add(node);
            throw new CircularDependencyException(cycle);
        }
        onPath.add(node);
        path.add(node);
        for (Expr.MetricRef ref : edges.getOrDefault(node, List.of())) {
            dfsCycle(key(ref.metricName(), ref.version()), edges, done, onPath, path);
        }
        path.remove(path.size() - 1);
        onPath.remove(node);
        done.add(node);
    }

    /** 校验查询时的过滤表达式（聚合前）：布尔、只引用物理列/派生维度、无指标引用。 */
    public Expr validateQueryFilter(String expression, LocalTable table,
                                    Map<String, DataType> availableDimensions) {
        Expr e = parse(expression);
        if (!ExpressionParser.referencedMetrics(e).isEmpty()) {
            throw new InvalidDefinitionException("过滤条件不能引用指标: " + expression);
        }
        Map<String, DataType> scope = new HashMap<>(availableDimensions);
        for (Column c : table.columns()) {
            scope.putIfAbsent(c.name(), c.type());
        }
        for (String col : ExpressionParser.referencedColumns(e)) {
            if (!scope.containsKey(col)) {
                throw new UnknownFieldException(col, "表 " + table.name() + " 的查询字段");
            }
        }
        DataType t = evaluator.inferType(e, scope::get);
        if (t != DataType.BOOLEAN) {
            throw new TypeMismatchException(expression, DataType.BOOLEAN, t.name());
        }
        return e;
    }

    private void validateRowFilter(String expression, LocalTable table) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        Expr e = parse(expression);
        if (!ExpressionParser.referencedMetrics(e).isEmpty()) {
            throw new InvalidDefinitionException("指标行过滤不能引用其它指标: " + expression);
        }
        for (String c : ExpressionParser.referencedColumns(e)) {
            if (table.column(c) == null) {
                throw new UnknownFieldException(c, "表 " + table.name());
            }
        }
        DataType t = evaluator.inferType(e, c -> {
            Column col = table.column(c);
            return col == null ? null : col.type();
        });
        if (t != DataType.BOOLEAN) {
            throw new TypeMismatchException(expression, DataType.BOOLEAN, t.name());
        }
    }

    private LocalTable requireTable(String tableName, DataSnapshot snapshot) {
        LocalTable table = snapshot.table(tableName);
        if (table == null) {
            throw new InvalidDefinitionException("快照中不存在表: " + tableName
                    + "（可用: " + snapshot.tables().keySet() + "）");
        }
        return table;
    }

    private static String key(MetricDefinition def) {
        return key(def.name(), def.version());
    }

    private static String key(String name, int version) {
        return name + "@v" + version;
    }
}
