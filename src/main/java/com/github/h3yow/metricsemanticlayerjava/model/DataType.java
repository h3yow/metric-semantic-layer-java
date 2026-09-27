package com.github.h3yow.metricsemanticlayerjava.model;

/**
 * 本地分析表字段 / 派生表达式支持的取值类型。
 */
public enum DataType {
    LONG,
    DOUBLE,
    STRING,
    BOOLEAN;

    /**
     * 数值类型才允许参与算术与聚合运算。
     */
    public boolean isNumeric() {
        return this == LONG || this == DOUBLE;
    }
}
