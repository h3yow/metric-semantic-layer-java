package com.github.h3yow.metricsemanticlayerjava.error;

/**
 * 表达式无法解析：语法错误、非法 token、括号不匹配等。
 */
public final class MalformedExpressionException extends DefinitionException {
    private final String expression;
    private final int position;

    public MalformedExpressionException(String expression, int position, String detail) {
        super("表达式语法错误（位置 " + position + "）: " + detail + "；原文: " + expression);
        this.expression = expression;
        this.position = position;
    }

    public String expression() {
        return expression;
    }

    public int position() {
        return position;
    }
}
