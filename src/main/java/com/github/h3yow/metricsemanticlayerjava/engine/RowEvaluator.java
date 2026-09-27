package com.github.h3yow.metricsemanticlayerjava.engine;

import com.github.h3yow.metricsemanticlayerjava.expr.Expr;
import com.github.h3yow.metricsemanticlayerjava.model.DataType;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 在单行 / 单组上下文上求值表达式，并做静态类型推断。
 *
 * <ul>
 *   <li>列引用通过 {@code columnTypes} / 行数据解析；</li>
 *   <li>指标引用（{@code 名称@版本}）通过 {@code metricTypes} / {@code metricValues} 解析，
 *       由调用方在依赖闭包内先算出被依赖指标的值，保证不跨版本；</li>
 *   <li>空值语义：算术/比较遇 null 传播为 null；布尔过滤上下文中 null 视为不通过；</li>
 *   <li>类型错误抛 {@link com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException}。</li>
 * </ul>
 */
public final class RowEvaluator {

    /** 行内取值函数：列名 -> 值（找不到返回 null）。 */
    public Object eval(Expr expr, Map<String, Object> row) {
        return eval(expr, name -> row == null ? null : row.get(name), name -> null);
    }

    /** 通用求值：列值解析器 + 指标值解析器。 */
    public Object eval(Expr expr,
                       Function<String, Object> columnValues,
                       Function<Expr.MetricRef, Object> metricValues) {
        switch (expr) {
            case Expr.Literal l -> {
                return l.value();
            }
            case Expr.ColumnRef c -> {
                return columnValues.apply(c.name());
            }
            case Expr.MetricRef m -> {
                Object v = metricValues.apply(m);
                if (v == null) {
                    throw new IllegalStateException(
                            "指标依赖值未提供: " + m.metricName() + "@" + m.version());
                }
                return v;
            }
            case Expr.Unary u -> {
                Object v = eval(u.operand(), columnValues, metricValues);
                if (v == null) {
                    return null;
                }
                return switch (u.op()) {
                    case "-" -> negate(u, v);
                    case "!" -> !truth(v);
                    default -> throw badOp(u.op(), v);
                };
            }
            case Expr.Binary b -> {
                return evalBinary(b, columnValues, metricValues);
            }
            case Expr.FunctionCall f -> {
                return evalFunction(f, columnValues, metricValues);
            }
        }
    }

    /** 布尔上下文：null 按 false（过滤不通过），非布尔类型报类型错误。 */
    public boolean evalBoolean(Expr expr, Map<String, Object> row) {
        Object v = eval(expr, row);
        if (v == null) {
            return false;
        }
        if (!(v instanceof Boolean)) {
            throw new com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException(
                    String.valueOf(expr), DataType.BOOLEAN, typeName(v));
        }
        return (Boolean) v;
    }

    public boolean evalBoolean(Expr expr,
                               Function<String, Object> columnValues,
                               Function<Expr.MetricRef, Object> metricValues) {
        Object v = eval(expr, columnValues, metricValues);
        if (v == null) {
            return false;
        }
        if (!(v instanceof Boolean)) {
            throw new com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException(
                    String.valueOf(expr), DataType.BOOLEAN, typeName(v));
        }
        return (Boolean) v;
    }

    /** 仅含列引用的表达式类型推断。 */
    public DataType inferType(Expr expr, Function<String, DataType> columnTypes) {
        return inferType(expr, columnTypes, m -> DataType.DOUBLE);
    }

    /** 含指标引用的类型推断；指标类型由解析器给出（聚合指标恒为数值）。 */
    public DataType inferType(Expr expr,
                              Function<String, DataType> columnTypes,
                              Function<Expr.MetricRef, DataType> metricTypes) {
        switch (expr) {
            case Expr.Literal l -> {
                return literalType(l.value());
            }
            case Expr.ColumnRef c -> {
                DataType t = columnTypes.apply(c.name());
                if (t == null) {
                    throw new com.github.h3yow.metricsemanticlayerjava.error.UnknownFieldException(
                            c.name(), "表达式");
                }
                return t;
            }
            case Expr.MetricRef m -> {
                DataType t = metricTypes.apply(m);
                if (t == null) {
                    throw new com.github.h3yow.metricsemanticlayerjava.error.UnknownFieldException(
                            m.metricName() + "@" + m.version(), "指标依赖");
                }
                return t;
            }
            case Expr.Unary u -> {
                DataType t = inferType(u.operand(), columnTypes, metricTypes);
                return switch (u.op()) {
                    case "-" -> requireNumeric(u, t);
                    case "!" -> {
                        if (t != DataType.BOOLEAN) {
                            throw mismatch(u, DataType.BOOLEAN, t);
                        }
                        yield DataType.BOOLEAN;
                    }
                    default -> throw new IllegalStateException("未知一元算符 " + u.op());
                };
            }
            case Expr.Binary b -> {
                return inferBinary(b, columnTypes, metricTypes);
            }
            case Expr.FunctionCall f -> {
                return inferFunction(f, columnTypes, metricTypes);
            }
        }
    }

    // ------------------------------------------------------------------
    // type inference helpers
    // ------------------------------------------------------------------

    private DataType inferBinary(Expr.Binary b,
                                 Function<String, DataType> columns,
                                 Function<Expr.MetricRef, DataType> metrics) {
        String op = b.op();
        if (op.equals("and") || op.equals("or")) {
            DataType lt = inferType(b.left(), columns, metrics);
            DataType rt = inferType(b.right(), columns, metrics);
            if (lt != DataType.BOOLEAN) {
                throw mismatch(b.left(), DataType.BOOLEAN, lt);
            }
            if (rt != DataType.BOOLEAN) {
                throw mismatch(b.right(), DataType.BOOLEAN, rt);
            }
            return DataType.BOOLEAN;
        }
        DataType l = inferType(b.left(), columns, metrics);
        DataType r = inferType(b.right(), columns, metrics);
        if (isCompareOp(op)) {
            if (isOrderingOp(op)) {
                // 大小比较只支持数值与字符串
                requireComparable(b.left(), l);
                requireComparable(b.right(), r);
            }
            // 数值之间（LONG/DOUBLE）允许互相比较；其余要求严格一致
            if (l != r && !(l.isNumeric() && r.isNumeric())) {
                throw new com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException(
                        op + " 两侧类型不一致: " + l + " vs " + r, l, r.name());
            }
            return DataType.BOOLEAN;
        }
        // arithmetic
        requireNumeric(b.left(), l);
        requireNumeric(b.right(), r);
        return (l == DataType.DOUBLE || r == DataType.DOUBLE) ? DataType.DOUBLE : DataType.LONG;
    }

    private DataType inferFunction(Expr.FunctionCall f,
                                   Function<String, DataType> columns,
                                   Function<Expr.MetricRef, DataType> metrics) {
        return switch (f.function()) {
            case "coalesce" -> {
                if (f.args().isEmpty()) {
                    throw new com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException(
                            "coalesce 至少需要一个参数");
                }
                DataType t = null;
                for (Expr a : f.args()) {
                    DataType at = inferType(a, columns, metrics);
                    if (t == null) {
                        t = at;
                    } else if (t != at) {
                        throw new com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException(
                                "coalesce 参数类型不一致: " + t + " vs " + at, t, at.name());
                    }
                }
                yield t;
            }
            case "if" -> {
                if (f.args().size() != 3) {
                    throw new com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException(
                            "if(cond, a, b) 需要 3 个参数");
                }
                DataType ct = inferType(f.args().get(0), columns, metrics);
                if (ct != DataType.BOOLEAN) {
                    throw mismatch(f.args().get(0), DataType.BOOLEAN, ct);
                }
                DataType a = inferType(f.args().get(1), columns, metrics);
                DataType b = inferType(f.args().get(2), columns, metrics);
                if (a != b) {
                    throw new com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException(
                            "if 两个分支类型不一致: " + a + " vs " + b, a, b.name());
                }
                yield a;
            }
            case "case_when" -> {
                if (f.args().size() < 2) {
                    throw new com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException(
                            "case_when 至少需要 (条件, 值) 一对参数，可再带一个末尾默认值");
                }
                // 末尾默认值：参数总数为奇数时，最后一个是默认值
                int pairs = f.args().size() / 2;
                boolean hasDefault = f.args().size() % 2 != 0;
                DataType t = null;
                for (int i = 0; i < pairs * 2; i += 2) {
                    DataType ct = inferType(f.args().get(i), columns, metrics);
                    if (ct != DataType.BOOLEAN) {
                        throw mismatch(f.args().get(i), DataType.BOOLEAN, ct);
                    }
                    DataType vt = inferType(f.args().get(i + 1), columns, metrics);
                    if (t == null) {
                        t = vt;
                    } else if (t != vt) {
                        throw new com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException(
                                "case_when 值类型不一致: " + t + " vs " + vt, t, vt.name());
                    }
                }
                if (hasDefault) {
                    DataType dt = inferType(f.args().get(f.args().size() - 1), columns, metrics);
                    if (t != null && t != dt) {
                        throw new com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException(
                                "case_when 默认值类型不一致: " + t + " vs " + dt, t, dt.name());
                    }
                    t = dt;
                }
                yield t;
            }
            case "abs", "neg" -> {
                DataType t = inferType(f.args().get(0), columns, metrics);
                yield requireNumeric(f.args().get(0), t);
            }
            case "round" -> {
                requireArity(f, 1, 2);
                DataType t = inferType(f.args().get(0), columns, metrics);
                yield requireNumeric(f.args().get(0), t);
            }
            case "length", "str_len" -> {
                requireArity(f, 1);
                DataType t = inferType(f.args().get(0), columns, metrics);
                if (t != DataType.STRING) {
                    throw mismatch(f.args().get(0), DataType.STRING, t);
                }
                yield DataType.LONG;
            }
            case "concat" -> {
                for (Expr a : f.args()) {
                    DataType t = inferType(a, columns, metrics);
                    if (t != DataType.STRING) {
                        throw mismatch(a, DataType.STRING, t);
                    }
                }
                yield DataType.STRING;
            }
            case "year", "month", "day" -> {
                requireArity(f, 1);
                DataType t = inferType(f.args().get(0), columns, metrics);
                if (!t.isNumeric()) {
                    throw mismatch(f.args().get(0), DataType.LONG, t);
                }
                yield DataType.LONG;
            }
            default -> throw new com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException(
                    "未知函数: " + f.function() + "；支持 coalesce/if/case_when/abs/round/length/concat/year/month/day");
        };
    }

    // ------------------------------------------------------------------
    // runtime evaluation
    // ------------------------------------------------------------------

    private Object evalBinary(Expr.Binary b,
                              Function<String, Object> columns,
                              Function<Expr.MetricRef, Object> metrics) {
        String op = b.op();
        if (op.equals("and")) {
            return evalBoolean(b.left(), columns, metrics) && evalBoolean(b.right(), columns, metrics);
        }
        if (op.equals("or")) {
            return evalBoolean(b.left(), columns, metrics) || evalBoolean(b.right(), columns, metrics);
        }
        Object l = eval(b.left(), columns, metrics);
        Object r = eval(b.right(), columns, metrics);
        if (l == null || r == null) {
            return null;
        }
        if (isCompareOp(op)) {
            return compare(op, l, r);
        }
        return arithmetic(op, l, r, b);
    }

    @SuppressWarnings("unchecked")
    private boolean compare(String op, Object l, Object r) {
        int cmp;
        if (l instanceof Number ln && r instanceof Number rn) {
            cmp = Double.compare(ln.doubleValue(), rn.doubleValue());
        } else if (l instanceof Comparable && l.getClass().equals(r.getClass())) {
            cmp = ((Comparable<Object>) l).compareTo(r);
        } else if (l instanceof Boolean && r instanceof Boolean) {
            cmp = Boolean.compare((Boolean) l, (Boolean) r);
        } else {
            throw new com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException(
                    op + " 两侧无法比较: " + typeName(l) + " vs " + typeName(r),
                    DataType.STRING, typeName(l));
        }
        return switch (op) {
            case "=" -> cmp == 0;
            case "!=" -> cmp != 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            default -> throw new IllegalStateException("未知比较符 " + op);
        };
    }

    private Object arithmetic(String op, Object l, Object r, Expr context) {
        if (!(l instanceof Number ln) || !(r instanceof Number rn)) {
            throw new com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException(
                    "算术运算要求数值: " + typeName(l) + " " + op + " " + typeName(r),
                    DataType.DOUBLE, typeName(l instanceof Number ? r : l));
        }
        boolean asDouble = l instanceof Double || r instanceof Double;
        if (asDouble) {
            double a = ln.doubleValue();
            double b = rn.doubleValue();
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0.0 ? Double.NaN : a / b;
                default -> throw badOp(op, l);
            };
        }
        long a = ln.longValue();
        long b = rn.longValue();
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> {
                if (b == 0L) {
                    throw new com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException(
                            "整数除零错误，表达式: " + context);
                }
                yield a / b;
            }
            default -> throw badOp(op, l);
        };
    }

    private Object evalFunction(Expr.FunctionCall f,
                                Function<String, Object> columns,
                                Function<Expr.MetricRef, Object> metrics) {
        return switch (f.function()) {
            case "coalesce" -> {
                Object result = null;
                for (Expr a : f.args()) {
                    result = eval(a, columns, metrics);
                    if (result != null) {
                        break;
                    }
                }
                yield result;
            }
            case "if" -> evalBoolean(f.args().get(0), columns, metrics)
                    ? eval(f.args().get(1), columns, metrics)
                    : eval(f.args().get(2), columns, metrics);
            case "case_when" -> {
                Object result = null;
                boolean matched = false;
                int pairsN = f.args().size() / 2;
                for (int i = 0; i < pairsN * 2; i += 2) {
                    if (evalBoolean(f.args().get(i), columns, metrics)) {
                        result = eval(f.args().get(i + 1), columns, metrics);
                        matched = true;
                        break;
                    }
                }
                if (!matched && f.args().size() % 2 != 0) {
                    result = eval(f.args().get(f.args().size() - 1), columns, metrics);
                }
                yield result;
            }
            case "abs" -> {
                Object v = eval(f.args().get(0), columns, metrics);
                if (v == null) yield null;
                yield v instanceof Long ? Math.abs((Long) v) : Math.abs(((Number) v).doubleValue());
            }
            case "neg" -> {
                Object v = eval(f.args().get(0), columns, metrics);
                if (v == null) yield null;
                yield v instanceof Long ? -(Long) v : -((Number) v).doubleValue();
            }
            case "round" -> {
                Object v = eval(f.args().get(0), columns, metrics);
                if (v == null) yield null;
                int digits = f.args().size() == 2
                        ? ((Number) eval(f.args().get(1), columns, metrics)).intValue() : 0;
                double scale = Math.pow(10, digits);
                yield Math.round(((Number) v).doubleValue() * scale) / scale;
            }
            case "length", "str_len" -> {
                Object v = eval(f.args().get(0), columns, metrics);
                yield v == null ? null : (long) v.toString().length();
            }
            case "concat" -> {
                StringBuilder sb = new StringBuilder();
                for (Expr a : f.args()) {
                    Object v = eval(a, columns, metrics);
                    if (v == null) yield null;
                    sb.append(v);
                }
                yield sb.toString();
            }
            case "year" -> datePart(f, columns, metrics, 0);
            case "month" -> datePart(f, columns, metrics, 1);
            case "day" -> datePart(f, columns, metrics, 2);
            default -> throw new com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException(
                    "未知函数: " + f.function());
        };
    }

    /**
     * 演示数据中日期以 yyyyMMdd 整数承载：year=v/10000，month=(v/100)%100，day=v%100。
     */
    private Long datePart(Expr.FunctionCall f,
                          Function<String, Object> columns,
                          Function<Expr.MetricRef, Object> metrics,
                          int part) {
        Object v = eval(f.args().get(0), columns, metrics);
        if (v == null) {
            return null;
        }
        long n = ((Number) v).longValue();
        return switch (part) {
            case 0 -> n / 10000;
            case 1 -> (n / 100) % 100;
            default -> n % 100;
        };
    }

    // ------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------

    private DataType literalType(Object v) {
        if (v instanceof Long) {
            return DataType.LONG;
        }
        if (v instanceof Number) {
            return DataType.DOUBLE;
        }
        if (v instanceof Boolean) {
            return DataType.BOOLEAN;
        }
        return DataType.STRING;
    }

    private boolean truth(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        throw new com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException(
                "'!' 要求布尔操作数", DataType.BOOLEAN, typeName(v));
    }

    private Object negate(Expr context, Object v) {
        if (v instanceof Long l) {
            return -l;
        }
        if (v instanceof Number n) {
            return -n.doubleValue();
        }
        throw mismatch(context, DataType.DOUBLE, typeName(v));
    }

    private DataType requireNumeric(Expr context, DataType t) {
        if (!t.isNumeric()) {
            throw mismatch(context, DataType.DOUBLE, t);
        }
        return t;
    }

    private void requireComparable(Expr context, DataType t) {
        if (t == DataType.BOOLEAN) {
            throw new com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException(
                    "布尔类型不支持大小比较: " + context, DataType.LONG, t.name());
        }
    }

    private boolean isCompareOp(String op) {
        return List.of("=", "!=", "<", "<=", ">", ">=").contains(op);
    }

    private boolean isOrderingOp(String op) {
        return List.of("<", "<=", ">", ">=").contains(op);
    }

    private void requireArity(Expr.FunctionCall f, int arity) {
        if (f.args().size() != arity) {
            throw new com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException(
                    f.function() + " 需要 " + arity + " 个参数，实际 " + f.args().size());
        }
    }

    private void requireArity(Expr.FunctionCall f, int min, int max) {
        if (f.args().size() < min || f.args().size() > max) {
            throw new com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException(
                    f.function() + " 需要 " + min + "~" + max + " 个参数，实际 " + f.args().size());
        }
    }

    private static String typeName(Object v) {
        return v == null ? "NULL" : v.getClass().getSimpleName();
    }

    private static com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException mismatch(
            Expr context, DataType expected, DataType actual) {
        return new com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException(
                String.valueOf(context), expected, actual.name());
    }

    private static com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException mismatch(
            Expr context, DataType expected, String actual) {
        return new com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException(
                String.valueOf(context), expected, actual);
    }

    private static IllegalStateException badOp(String op, Object v) {
        return new IllegalStateException("不支持的运算 '" + op + "' 作用于 " + typeName(v));
    }
}
