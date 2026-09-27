package com.github.h3yow.metricsemanticlayerjava.model;

/**
 * 本地分析表的一列。
 *
 * @param name 列名
 * @param type 列类型
 */
public record Column(String name, DataType type) {
    public Column {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("列名不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("列类型不能为 null");
        }
    }
}
