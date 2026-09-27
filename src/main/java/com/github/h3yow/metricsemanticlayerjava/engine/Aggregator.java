package com.github.h3yow.metricsemanticlayerjava.engine;

import com.github.h3yow.metricsemanticlayerjava.error.InvalidDefinitionException;
import com.github.h3yow.metricsemanticlayerjava.error.TypeMismatchException;
import com.github.h3yow.metricsemanticlayerjava.model.AggType;
import com.github.h3yow.metricsemanticlayerjava.model.DataType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 分组聚合算子。
 *
 * <ul>
 *   <li>SUM/AVG/MIN/MAX 只接受数值；类型不符直接抛 {@link TypeMismatchException}，不静默归零；</li>
 *   <li>COUNT 计非空行数；COUNT_DISTINCT 计非空去重值个数；</li>
 *   <li>AVG 恒定返回 DOUBLE；SUM 全 LONG 时返回 LONG，否则 DOUBLE；</li>
 *   <li>空组：COUNT 类返回 0L；其余返回 null（由上层决定语义，绝不伪造 0）。</li>
 * </ul>
 */
public final class Aggregator {

    private Aggregator() {
    }

    /**
     * 对一组已经过滤掉 null 的值做聚合。
     */
    public static Object aggregate(AggType agg, List<Object> values) {
        Objects.requireNonNull(agg, "聚合方式不能为 null");
        List<Object> nonNull = values == null ? List.of()
                : values.stream().filter(Objects::nonNull).toList();
        return switch (agg) {
            case COUNT -> (long) nonNull.size();
            case COUNT_DISTINCT -> countDistinct(nonNull);
            case SUM -> sum(nonNull);
            case AVG -> avg(nonNull);
            case MIN -> minMax(nonNull, true);
            case MAX -> minMax(nonNull, false);
        };
    }

    private static Long countDistinct(List<Object> values) {
        Set<Object> seen = new LinkedHashSet<>(values);
        return (long) seen.size();
    }

    private static Object sum(List<Object> values) {
        if (values.isEmpty()) {
            return null;
        }
        boolean asDouble = false;
        double dsum = 0.0;
        long lsum = 0L;
        for (Object v : values) {
            if (!(v instanceof Number n)) {
                throw new TypeMismatchException("SUM 输入", DataType.DOUBLE,
                        v == null ? "NULL" : v.getClass().getSimpleName());
            }
            if (v instanceof Double || v instanceof Float) {
                asDouble = true;
            }
            if (asDouble) {
                dsum += n.doubleValue();
            } else {
                lsum += n.longValue();
            }
        }
        return asDouble ? dsum : lsum;
    }

    private static Object avg(List<Object> values) {
        if (values.isEmpty()) {
            return null;
        }
        double sum = 0.0;
        for (Object v : values) {
            if (!(v instanceof Number n)) {
                throw new TypeMismatchException("AVG 输入", DataType.DOUBLE,
                        v == null ? "NULL" : v.getClass().getSimpleName());
            }
            sum += n.doubleValue();
        }
        return sum / values.size();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object minMax(List<Object> values, boolean min) {
        if (values.isEmpty()) {
            return null;
        }
        Object best = values.get(0);
        for (Object v : values) {
            if (v instanceof Number && best instanceof Number) {
                double cmp = ((Number) v).doubleValue() - ((Number) best).doubleValue();
                if ((min && cmp < 0) || (!min && cmp > 0)) {
                    best = v;
                }
            } else if (v.getClass().equals(best.getClass()) && v instanceof Comparable) {
                int cmp = ((Comparable) v).compareTo(best);
                if ((min && cmp < 0) || (!min && cmp > 0)) {
                    best = v;
                }
            } else {
                throw new TypeMismatchException((min ? "MIN" : "MAX") + " 输入",
                        DataType.DOUBLE, v.getClass().getSimpleName());
            }
        }
        return best;
    }

    /** 聚合结果类型，供定义期校验使用。 */
    public static DataType resultType(AggType agg, DataType inputType) {
        return switch (agg) {
            case COUNT, COUNT_DISTINCT -> DataType.LONG;
            case AVG -> DataType.DOUBLE;
            case SUM -> {
                if (inputType != null && !inputType.isNumeric()) {
                    throw new TypeMismatchException("SUM 源列", DataType.DOUBLE, inputType.name());
                }
                yield inputType == DataType.LONG ? DataType.LONG : DataType.DOUBLE;
            }
            case MIN, MAX -> {
                if (inputType == null) {
                    throw new InvalidDefinitionException("MIN/MAX 必须指定源列");
                }
                yield inputType;
            }
        };
    }
}
