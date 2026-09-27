package com.github.h3yow.metricsemanticlayerjava.error;

import com.github.h3yow.metricsemanticlayerjava.model.DataType;

/**
 * 表达式类型不匹配：如对字符串做乘法、布尔参与 SUM、过滤条件不是布尔型等。
 */
public final class TypeMismatchException extends DefinitionException {
    private final String expression;
    private final DataType expected;
    private final String actual;

    public TypeMismatchException(String expression, DataType expected, String actual) {
        super("类型不匹配: 表达式 '" + expression + "' 期望 " + expected + "，实际 " + actual);
        this.expression = expression;
        this.expected = expected;
        this.actual = actual;
    }

    public String expression() {
        return expression;
    }

    public DataType expected() {
        return expected;
    }

    public String actual() {
        return actual;
    }
}
