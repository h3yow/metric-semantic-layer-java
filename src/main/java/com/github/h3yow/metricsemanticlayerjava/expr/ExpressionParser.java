package com.github.h3yow.metricsemanticlayerjava.expr;

import com.github.h3yow.metricsemanticlayerjava.error.MalformedExpressionException;

import java.util.ArrayList;
import java.util.List;

/**
 * 表达式解析器：把声明式字符串解析成 {@link Expr} AST。
 *
 * <p>文法（见 {@link Expr} 包注释）。关键字 {@code and}/{@code or}/{@code true}/{@code false}
 * 大小写不敏感；标识符支持中文/字母/下划线开头；{@code 标识符@整数} 表示对指标固定版本的引用。</p>
 */
public final class ExpressionParser {

    /**
     * 解析表达式；语法不合法时抛 {@link MalformedExpressionException}。
     */
    public Expr parse(String source) {
        if (source == null || source.isBlank()) {
            throw new MalformedExpressionException(String.valueOf(source), 0, "表达式为空");
        }
        List<Token> tokens = Lexer.tokenize(source);
        Parser parser = new Parser(source, tokens);
        Expr expr = parser.parseOr();
        if (parser.peek().type != TokenType.EOF) {
            throw new MalformedExpressionException(source, parser.peek().position,
                    "存在未消费的 token '" + parser.peek().text + "'");
        }
        return expr;
    }

    /** 收集 AST 中引用的普通列名（不含指标引用、不含函数名）。 */
    public static List<String> referencedColumns(Expr expr) {
        List<String> out = new ArrayList<>();
        collectColumns(expr, out);
        return out;
    }

    private static void collectColumns(Expr expr, List<String> out) {
        switch (expr) {
            case Expr.Literal l -> { }
            case Expr.ColumnRef c -> out.add(c.name());
            case Expr.MetricRef m -> { }
            case Expr.Unary u -> collectColumns(u.operand(), out);
            case Expr.Binary b -> {
                collectColumns(b.left(), out);
                collectColumns(b.right(), out);
            }
            case Expr.FunctionCall f -> f.args().forEach(a -> collectColumns(a, out));
        }
    }

    /** 收集 AST 中引用的其它指标固定版本。 */
    public static List<Expr.MetricRef> referencedMetrics(Expr expr) {
        List<Expr.MetricRef> out = new ArrayList<>();
        collectMetrics(expr, out);
        return out;
    }

    private static void collectMetrics(Expr expr, List<Expr.MetricRef> out) {
        switch (expr) {
            case Expr.Literal l -> { }
            case Expr.ColumnRef c -> { }
            case Expr.MetricRef m -> out.add(m);
            case Expr.Unary u -> collectMetrics(u.operand(), out);
            case Expr.Binary b -> {
                collectMetrics(b.left(), out);
                collectMetrics(b.right(), out);
            }
            case Expr.FunctionCall f -> f.args().forEach(a -> collectMetrics(a, out));
        }
    }

    // ------------------------------------------------------------------
    // token
    // ------------------------------------------------------------------

    private enum TokenType {
        NUMBER, STRING, IDENT,
        OP, AND, OR, TRUE, FALSE,
        AT, LPAREN, RPAREN, COMMA, EOF
    }

    private record Token(TokenType type, String text, Object value, int position) {
    }

    // ------------------------------------------------------------------
    // lexer
    // ------------------------------------------------------------------

    private static final class Lexer {
        private final String src;
        private int pos;
        private final List<Token> tokens = new ArrayList<>();

        private Lexer(String src) {
            this.src = src;
        }

        static List<Token> tokenize(String src) {
            Lexer lx = new Lexer(src);
            lx.run();
            return lx.tokens;
        }

        private void run() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                } else if (c == '\'') {
                    readString('\'');
                } else if (c == '"') {
                    readString('"');
                } else if (Character.isDigit(c)) {
                    readNumber();
                } else if (isIdentStart(c)) {
                    readIdent();
                } else {
                    readSymbol();
                }
            }
            tokens.add(new Token(TokenType.EOF, "", null, pos));
        }

        private boolean isIdentStart(char c) {
            return Character.isLetter(c) || c == '_';
        }

        private void readString(char quote) {
            int start = pos;
            pos++; // skip opening quote
            StringBuilder sb = new StringBuilder();
            while (pos < src.length() && src.charAt(pos) != quote) {
                char c = src.charAt(pos);
                if (c == '\\' && pos + 1 < src.length()) {
                    char next = src.charAt(pos + 1);
                    switch (next) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case '\\' -> sb.append('\\');
                        case '\'' -> sb.append('\'');
                        case '"' -> sb.append('"');
                        default -> sb.append(next);
                    }
                    pos += 2;
                } else {
                    sb.append(c);
                    pos++;
                }
            }
            if (pos >= src.length()) {
                throw new MalformedExpressionException(src, start, "字符串缺少结束引号");
            }
            pos++; // skip closing quote
            tokens.add(new Token(TokenType.STRING, sb.toString(), sb.toString(), start));
        }

        private void readNumber() {
            int start = pos;
            boolean dot = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' && !dot) {
                    dot = true;
                    pos++;
                } else {
                    break;
                }
            }
            String text = src.substring(start, pos);
            // 注意：不能用三元表达式，否则 Long/Double 两个分支会按数值公共类型 double 混合计算
            Object value;
            if (dot) {
                value = Double.valueOf(text);
            } else {
                value = Long.valueOf(text);
            }
            tokens.add(new Token(TokenType.NUMBER, text, value, start));
        }

        private void readIdent() {
            int start = pos;
            while (pos < src.length() && (isIdentStart(src.charAt(pos)) || Character.isDigit(src.charAt(pos)))) {
                pos++;
            }
            String text = src.substring(start, pos);
            TokenType type = switch (text.toLowerCase()) {
                case "and" -> TokenType.AND;
                case "or" -> TokenType.OR;
                case "true" -> TokenType.TRUE;
                case "false" -> TokenType.FALSE;
                default -> TokenType.IDENT;
            };
            Object value = switch (type) {
                case TRUE -> Boolean.TRUE;
                case FALSE -> Boolean.FALSE;
                default -> text;
            };
            tokens.add(new Token(type, text, value, start));
        }

        private void readSymbol() {
            int start = pos;
            char c = src.charAt(pos);
            switch (c) {
                case '(' -> { tokens.add(new Token(TokenType.LPAREN, "(", null, start)); pos++; }
                case ')' -> { tokens.add(new Token(TokenType.RPAREN, ")", null, start)); pos++; }
                case ',' -> { tokens.add(new Token(TokenType.COMMA, ",", null, start)); pos++; }
                case '@' -> { tokens.add(new Token(TokenType.AT, "@", null, start)); pos++; }
                case '+', '-', '*', '/' -> {
                    tokens.add(new Token(TokenType.OP, String.valueOf(c), null, start));
                    pos++;
                }
                case '=' -> { tokens.add(new Token(TokenType.OP, "=", null, start)); pos++; }
                case '!' -> {
                    if (pos + 1 < src.length() && src.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.OP, "!=", null, start));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.OP, "!", null, start));
                        pos++;
                    }
                }
                case '<' -> {
                    if (pos + 1 < src.length() && src.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.OP, "<=", null, start));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.OP, "<", null, start));
                        pos++;
                    }
                }
                case '>' -> {
                    if (pos + 1 < src.length() && src.charAt(pos + 1) == '=') {
                        tokens.add(new Token(TokenType.OP, ">=", null, start));
                        pos += 2;
                    } else {
                        tokens.add(new Token(TokenType.OP, ">", null, start));
                        pos++;
                    }
                }
                default -> throw new MalformedExpressionException(src, start, "非法字符 '" + c + "'");
            }
        }
    }

    // ------------------------------------------------------------------
    // recursive descent parser
    // ------------------------------------------------------------------

    private static final class Parser {
        private final String src;
        private final List<Token> tokens;
        private int idx;

        private Parser(String src, List<Token> tokens) {
            this.src = src;
            this.tokens = tokens;
        }

        private Token peek() {
            return tokens.get(idx);
        }

        private Token advance() {
            Token t = tokens.get(idx);
            if (idx < tokens.size() - 1) {
                idx++;
            }
            return t;
        }

        private void expect(TokenType type, String what) {
            if (peek().type != type) {
                throw new MalformedExpressionException(src, peek().position,
                        "期望 " + what + "，但遇到 '" + peek().text + "'");
            }
            advance();
        }

        Expr parseOr() {
            Expr left = parseAnd();
            while (peek().type == TokenType.OR) {
                Token op = advance();
                Expr right = parseAnd();
                left = new Expr.Binary("or", left, right);
                checkOpPos(op);
            }
            return left;
        }

        Expr parseAnd() {
            Expr left = parseComparison();
            while (peek().type == TokenType.AND) {
                Token op = advance();
                Expr right = parseComparison();
                left = new Expr.Binary("and", left, right);
                checkOpPos(op);
            }
            return left;
        }

        Expr parseComparison() {
            Expr left = parseAdditive();
            if (peek().type == TokenType.OP
                    && List.of("=", "!=", "<", "<=", ">", ">=").contains(peek().text)) {
                Token op = advance();
                Expr right = parseAdditive();
                return new Expr.Binary(op.text, left, right);
            }
            return left;
        }

        Expr parseAdditive() {
            Expr left = parseMultiplicative();
            while (peek().type == TokenType.OP && (peek().text.equals("+") || peek().text.equals("-"))) {
                Token op = advance();
                Expr right = parseMultiplicative();
                left = new Expr.Binary(op.text, left, right);
            }
            return left;
        }

        Expr parseMultiplicative() {
            Expr left = parseUnary();
            while (peek().type == TokenType.OP && (peek().text.equals("*") || peek().text.equals("/"))) {
                Token op = advance();
                Expr right = parseUnary();
                left = new Expr.Binary(op.text, left, right);
            }
            return left;
        }

        Expr parseUnary() {
            if (peek().type == TokenType.OP && (peek().text.equals("-") || peek().text.equals("!"))) {
                Token op = advance();
                return new Expr.Unary(op.text, parseUnary());
            }
            return parsePrimary();
        }

        Expr parsePrimary() {
            Token t = peek();
            switch (t.type) {
                case NUMBER -> {
                    advance();
                    return new Expr.Literal(t.value);
                }
                case STRING -> {
                    advance();
                    return new Expr.Literal(t.value);
                }
                case TRUE -> {
                    advance();
                    return new Expr.Literal(Boolean.TRUE);
                }
                case FALSE -> {
                    advance();
                    return new Expr.Literal(Boolean.FALSE);
                }
                case LPAREN -> {
                    advance();
                    Expr inner = parseOr();
                    expect(TokenType.RPAREN, "')'");
                    return inner;
                }
                case IDENT -> {
                    advance();
                    String name = t.text;
                    // 指标固定版本引用: name@version
                    if (peek().type == TokenType.AT) {
                        advance();
                        Token ver = peek();
                        if (ver.type != TokenType.NUMBER || !(ver.value instanceof Long v)) {
                            throw new MalformedExpressionException(src, peek().position,
                                    "'@' 后必须是整数版本号");
                        }
                        advance();
                        if (v < 1 || v > Integer.MAX_VALUE) {
                            throw new MalformedExpressionException(src, ver.position,
                                    "版本号必须是 1 以上的整数");
                        }
                        return new Expr.MetricRef(name, Math.toIntExact(v));
                    }
                    // 函数调用
                    if (peek().type == TokenType.LPAREN) {
                        advance();
                        List<Expr> args = new ArrayList<>();
                        if (peek().type != TokenType.RPAREN) {
                            args.add(parseOr());
                            while (peek().type == TokenType.COMMA) {
                                advance();
                                args.add(parseOr());
                            }
                        }
                        expect(TokenType.RPAREN, "')'");
                        return new Expr.FunctionCall(name.toLowerCase(), args);
                    }
                    return new Expr.ColumnRef(name);
                }
                default -> throw new MalformedExpressionException(src, t.position,
                        "无法解析的 token '" + t.text + "'");
            }
        }

        private void checkOpPos(Token op) {
            // 预留：关键字大小写信息可在此利用；当前无需动作。
            if (op.position < 0) {
                throw new MalformedExpressionException(src, 0, "内部解析错误");
            }
        }
    }
}
