package com.github.h3yow.metricsemanticlayerjava.error;

/**
 * 定义中引用了表上不存在的列，或查询分组/筛选使用了未知字段。
 */
public final class UnknownFieldException extends DefinitionException {
    private final String field;
    private final String scope;

    public UnknownFieldException(String field, String scope) {
        super("未知字段 '" + field + "'（作用域: " + scope + "）");
        this.field = field;
        this.scope = scope;
    }

    public String field() {
        return field;
    }

    public String scope() {
        return scope;
    }
}
