package com.github.h3yow.metricsemanticlayerjava.expr;

import com.github.h3yow.metricsemanticlayerjava.exception.TypeMismatchException;
import com.github.h3yow.metricsemanticlayerjava.exception.UnknownFieldException;
import com.github.h3yow.metricsemanticlayerjava.model.DataType;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * Small expression DSL used by filters and derived columns:
 *
 * <pre>{@code
 * import static ...expr.Expressions.*;
 * col("amount").gt(lit(100L)).and(col("region").eq(lit("US")))
 * col("price").times(col("qty"))
 * }</pre>
 *
 * Instances are immutable and thread-safe; a bound expression may be evaluated
 * concurrently over different rows.
 */
public final class Expressions {

    private Expressions() {
    }

    // ---------------------------------------------------------------- factories

    public static Ref col(String name) {
        return new Ref(name);
    }

    public static Literal lit(long v) {
        return new Literal(v, DataType.LONG);
    }

    public static Literal lit(double v) {
        return new Literal(v, DataType.DOUBLE);
    }

    public static Literal lit(boolean v) {
        return new Literal(v, DataType.BOOLEAN);
    }

    public static Literal lit(String v) {
        return new Literal(v, DataType.STRING);
    }

    public static Arithmetic plus(Expr l, Expr r) {
        return new Arithmetic(l, r, "+", Long::sum, Double::sum);
    }

    public static Arithmetic minus(Expr l, Expr r) {
        return new Arithmetic(l, r, "-", (a, b) -> a - b, (a, b) -> a - b);
    }

    public static Arithmetic times(Expr l, Expr r) {
        return new Arithmetic(l, r, "*", (a, b) -> a * b, (a, b) -> a * b);
    }

    public static Arithmetic div(Expr l, Expr r) {
        return new Arithmetic(l, r, "/", (a, b) -> a / b, (a, b) -> a / b);
    }

    public static Comparison eq(Expr l, Expr r) {
        return new Comparison(l, r, "=", Object::equals);
    }

    public static Comparison ne(Expr l, Expr r) {
        return new Comparison(l, r, "!=", (a, b) -> !a.equals(b));
    }

    public static Comparison gt(Expr l, Expr r) {
        return new Comparison(l, r, ">", (a, b) -> compare(a, b) > 0);
    }

    public static Comparison ge(Expr l, Expr r) {
        return new Comparison(l, r, ">=", (a, b) -> compare(a, b) >= 0);
    }

    public static Comparison lt(Expr l, Expr r) {
        return new Comparison(l, r, "<", (a, b) -> compare(a, b) < 0);
    }

    public static Comparison le(Expr l, Expr r) {
        return new Comparison(l, r, "<=", (a, b) -> compare(a, b) <= 0);
    }

    public static Logic and(Expr l, Expr r) {
        return new Logic(l, r, "&&", (a, b) -> a && b);
    }

    public static Logic or(Expr l, Expr r) {
        return new Logic(l, r, "||", (a, b) -> a || b);
    }

    public static Not not(Expr e) {
        return new Not(e);
    }

    /**
     * Conditional value: {@code cond(when, then, otherwise)}.
     * The condition must be BOOLEAN and the two branches must share a type.
     */
    public static Cond cond(Expr when, Expr then, Expr otherwise) {
        return new Cond(when, then, otherwise);
    }

    /** True when the referenced/expression value is not null. */
    public static IsNotNull isNotNull(Expr e) {
        return new IsNotNull(e);
    }

    @SuppressWarnings("unchecked")
    private static int compare(Object a, Object b) {
        return ((Comparable<Object>) a).compareTo(b);
    }

    public static boolean isNumeric(DataType t) {
        return t == DataType.LONG || t == DataType.DOUBLE;
    }

    // ---------------------------------------------------------------- nodes

    /** Column reference; binding rejects unknown fields. */
    public static final class Ref implements Expr {
        private final String name;

        Ref(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        @Override
        public DataType bind(Scope scope, String context) {
            return scope.typeOf(name)
                    .orElseThrow(() -> new UnknownFieldException(name, context));
        }

        @Override
        public Object eval(Map<String, Object> row) {
            return row.get(name);
        }

        @Override
        public String describe() {
            return name;
        }
    }

    /** Typed literal. */
    public static final class Literal implements Expr {
        private final Object value;
        private final DataType type;

        Literal(Object value, DataType type) {
            this.value = value;
            this.type = type;
        }

        public Object value() {
            return value;
        }

        @Override
        public DataType bind(Scope scope, String context) {
            return type;
        }

        @Override
        public Object eval(Map<String, Object> row) {
            return value;
        }

        @Override
        public String describe() {
            return switch (type) {
                case STRING -> "'" + value + "'";
                default -> String.valueOf(value);
            };
        }
    }

    /** Numeric arithmetic; LONG op LONG stays LONG, otherwise promoted to DOUBLE. */
    public static final class Arithmetic implements Expr {
        private final Expr left, right;
        private final String op;
        private final BiFunction<Long, Long, Long> onLong;
        private final BiFunction<Double, Double, Double> onDouble;

        Arithmetic(Expr left, Expr right, String op,
                   BiFunction<Long, Long, Long> onLong,
                   BiFunction<Double, Double, Double> onDouble) {
            this.left = left;
            this.right = right;
            this.op = op;
            this.onLong = onLong;
            this.onDouble = onDouble;
        }

        @Override
        public DataType bind(Scope scope, String context) {
            DataType lt = left.bind(scope, context);
            DataType rt = right.bind(scope, context);
            if (!isNumeric(lt) || !isNumeric(rt)) {
                throw TypeMismatchException.expression(
                        describe(), "numeric operands", lt + " " + op + " " + rt);
            }
            return lt == DataType.LONG && rt == DataType.LONG ? DataType.LONG : DataType.DOUBLE;
        }

        @Override
        public Object eval(Map<String, Object> row) {
            Object a = left.eval(row);
            Object b = right.eval(row);
            if (a == null || b == null) {
                return null;
            }
            if (a instanceof Long la && b instanceof Long lb) {
                return onLong.apply(la, lb);
            }
            return onDouble.apply(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }

        @Override
        public String describe() {
            return "(" + left.describe() + " " + op + " " + right.describe() + ")";
        }
    }

    /** Equality/ordering comparison; operands must share a comparable type. */
    public static final class Comparison implements Expr {
        private final Expr left, right;
        private final String op;
        private final BiFunction<Object, Object, Boolean> fn;

        Comparison(Expr left, Expr right, String op, BiFunction<Object, Object, Boolean> fn) {
            this.left = left;
            this.right = right;
            this.op = op;
            this.fn = fn;
        }

        @Override
        public DataType bind(Scope scope, String context) {
            DataType lt = left.bind(scope, context);
            DataType rt = right.bind(scope, context);
            if (lt != rt) {
                throw TypeMismatchException.expression(
                        describe(), "same type on both sides of '" + op + "'", lt + " vs " + rt);
            }
            return DataType.BOOLEAN;
        }

        @Override
        public Object eval(Map<String, Object> row) {
            Object a = left.eval(row);
            Object b = right.eval(row);
            if (a == null || b == null) {
                return null;
            }
            return fn.apply(a, b);
        }

        @Override
        public String describe() {
            return "(" + left.describe() + " " + op + " " + right.describe() + ")";
        }
    }

    /** Boolean AND/OR. */
    public static final class Logic implements Expr {
        private final Expr left, right;
        private final String op;
        private final BiFunction<Boolean, Boolean, Boolean> fn;

        Logic(Expr left, Expr right, String op, BiFunction<Boolean, Boolean, Boolean> fn) {
            this.left = left;
            this.right = right;
            this.op = op;
            this.fn = fn;
        }

        @Override
        public DataType bind(Scope scope, String context) {
            checkBool(left.bind(scope, context));
            checkBool(right.bind(scope, context));
            return DataType.BOOLEAN;
        }

        private void checkBool(DataType t) {
            if (t != DataType.BOOLEAN) {
                throw TypeMismatchException.expression(describe(), "BOOLEAN operand", t.toString());
            }
        }

        @Override
        public Object eval(Map<String, Object> row) {
            Object a = left.eval(row);
            Object b = right.eval(row);
            if (a == null || b == null) {
                return null;
            }
            return fn.apply((Boolean) a, (Boolean) b);
        }

        @Override
        public String describe() {
            return "(" + left.describe() + " " + op + " " + right.describe() + ")";
        }
    }

    /** Boolean negation. */
    public static final class Not implements Expr {
        private final Expr inner;

        Not(Expr inner) {
            this.inner = inner;
        }

        @Override
        public DataType bind(Scope scope, String context) {
            DataType t = inner.bind(scope, context);
            if (t != DataType.BOOLEAN) {
                throw TypeMismatchException.expression(describe(), "BOOLEAN operand", t.toString());
            }
            return DataType.BOOLEAN;
        }

        @Override
        public Object eval(Map<String, Object> row) {
            Object v = inner.eval(row);
            return v == null ? null : !(Boolean) v;
        }

        @Override
        public String describe() {
            return "(!" + inner.describe() + ")";
        }
    }

    /** NULL check; binds to any type. */
    public static final class IsNotNull implements Expr {
        private final Expr inner;

        IsNotNull(Expr inner) {
            this.inner = inner;
        }

        @Override
        public DataType bind(Scope scope, String context) {
            inner.bind(scope, context);
            return DataType.BOOLEAN;
        }

        @Override
        public Object eval(Map<String, Object> row) {
            return inner.eval(row) != null;
        }

        @Override
        public String describe() {
            return "(" + inner.describe() + " is not null)";
        }
    }

    /** CASE WHEN then ELSE otherwise END (strict, same-type branches). */
    public static final class Cond implements Expr {
        private final Expr when, then, otherwise;

        Cond(Expr when, Expr then, Expr otherwise) {
            this.when = when;
            this.then = then;
            this.otherwise = otherwise;
        }

        @Override
        public DataType bind(Scope scope, String context) {
            DataType wt = when.bind(scope, context);
            if (wt != DataType.BOOLEAN) {
                throw TypeMismatchException.expression(describe(), "BOOLEAN condition", wt.toString());
            }
            DataType tt = then.bind(scope, context);
            DataType ot = otherwise.bind(scope, context);
            if (tt != ot) {
                throw TypeMismatchException.expression(
                        describe(), "same type for both branches", tt + " vs " + ot);
            }
            return tt;
        }

        @Override
        public Object eval(Map<String, Object> row) {
            Object c = when.eval(row);
            if (c == null) {
                return otherwise.eval(row); // unknown condition -> else branch
            }
            return Boolean.TRUE.equals(c) ? then.eval(row) : otherwise.eval(row);
        }

        @Override
        public String describe() {
            return "(CASE WHEN " + when.describe() + " THEN " + then.describe()
                    + " ELSE " + otherwise.describe() + " END)";
        }
    }

}
