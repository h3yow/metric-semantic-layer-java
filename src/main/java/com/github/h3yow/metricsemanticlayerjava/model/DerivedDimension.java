package com.github.h3yow.metricsemanticlayerjava.model;

/**
 * 派生维度：基于源表列计算出来的分组维度。
 * 表达式中不允许引用其它指标（指标只能依赖指标，维度不能依赖指标）。
 *
 * @param name       派生维度名
 * @param table      来源表
 * @param expression 派生表达式，如 "case_when(region='CN','境内','境外')"、"year(created_at)"
 * @param resultType 声明的结果类型
 */
public record DerivedDimension(
        String name,
        String table,
        String expression,
        DataType resultType
) {
    public DerivedDimension {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("维度名不能为空");
        }
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("来源表不能为空");
        }
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("派生表达式不能为空");
        }
        if (resultType == null) {
            throw new IllegalArgumentException("结果类型不能为 null");
        }
    }
}
