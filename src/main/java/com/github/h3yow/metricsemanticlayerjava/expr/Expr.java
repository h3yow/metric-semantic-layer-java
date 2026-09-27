package com.github.h3yow.metricsemanticlayerjava.expr;

import java.util.List;

/**
 * 表达式 AST。表达式语法（子集）：
 * <pre>
 *   expr   := or
 *   or     := and ( 'or' and )*
 *   and    := cmp ( 'and' cmp )*
 *   cmp    := add ( ('=' | '!=' | '&lt;' | '&lt;=' | '&gt;' | '&gt;=') add )?
 *   add    := mul ( ('+' | '-') mul )*
 *   mul    := unary ( ('*' | '/') unary )*
 *   unary  := ('-' | '!') unary | primary
 *   primary:= number | string | true | false
 *           | ident ('@' integer)?            -- 列名 或 指标名@版本
 *           | ident '(' args ')'              -- 函数调用
 *           | '(' expr ')'
 * </pre>
 * 标识符带 {@code @版本号} 时表示对另一指标版本的引用（只允许出现在指标表达式中）。
 */
public sealed interface Expr
        permits Expr.Literal, Expr.ColumnRef, Expr.MetricRef,
                Expr.Unary, Expr.Binary, Expr.FunctionCall {

    /** 字面量。 */
    record Literal(Object value) implements Expr {
    }

    /** 列引用（源表列或派生维度列）。 */
    record ColumnRef(String name) implements Expr {
    }

    /** 对其它指标某个固定版本的引用：{@code 指标名@版本号}。 */
    record MetricRef(String metricName, int version) implements Expr {
    }

    /** 一元运算：'-'（负号）或 '!'（逻辑非）。 */
    record Unary(String op, Expr operand) implements Expr {
    }

    /** 二元运算。 */
    record Binary(String op, Expr left, Expr right) implements Expr {
    }

    /** 函数调用，如 {@code coalesce(a, b)}、{@code year(created_at)}。 */
    record FunctionCall(String function, List<Expr> args) implements Expr {
        public FunctionCall {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }
}
