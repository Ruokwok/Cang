package lexer;

import java.util.Map;
import java.util.HashMap;

public enum TokenType {

    // Literals
    INT_LIT, LONG_LIT, FLOAT_LIT, DOUBLE_LIT, STRING_LIT, STR_LIT,

    // Identifier
    IDENT,

    // Keywords
    CLASS, IF, ELSE, WHILE, FOR, RETURN, BREAK, CONTINUE,
    NEW, THIS, STATIC, VOID, TRUE, FALSE, VAR, FUNC, DEF,
    NAMESPACE, IMPORT, NULL, SWITCH, CASE, DEFAULT, LIKE, FINAL, NATIVE, FREE, THREAD, TRY, CATCH, FINALLY, THROW,

    // Type keywords
    T_BYTE, T_INT, T_LONG, T_FLOAT, T_DOUBLE, T_BOOL, T_STRING, T_STR,

    // Operators
    PLUS, MINUS, STAR, SLASH, PERCENT, DOUBLE_SLASH,
    ASSIGN, EQ, NEQ, LT, GT, LE, GE,
    AND, OR, NOT,
    INC, DEC,
    PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN,
    QUESTION, COLON, ARROW, DOUBLE_COLON,

    // Delimiters
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET, SEMICOLON, COMMA, DOT,

    // Special
    EOF;

    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put("class", CLASS);
        KEYWORDS.put("if", IF);
        KEYWORDS.put("else", ELSE);
        KEYWORDS.put("while", WHILE);
        KEYWORDS.put("for", FOR);
        KEYWORDS.put("return", RETURN);
        KEYWORDS.put("break", BREAK);
        KEYWORDS.put("continue", CONTINUE);
        KEYWORDS.put("new", NEW);
        KEYWORDS.put("this", THIS);
        KEYWORDS.put("static", STATIC);
        KEYWORDS.put("void", VOID);
        KEYWORDS.put("true", TRUE);
        KEYWORDS.put("false", FALSE);
        KEYWORDS.put("null", NULL);

        KEYWORDS.put("byte", T_BYTE);
        KEYWORDS.put("int", T_INT);
        KEYWORDS.put("long", T_LONG);
        KEYWORDS.put("float", T_FLOAT);
        KEYWORDS.put("double", T_DOUBLE);
        KEYWORDS.put("bool", T_BOOL);
        KEYWORDS.put("String", T_STRING);
        KEYWORDS.put("string", T_STR);
        KEYWORDS.put("var", VAR);
        KEYWORDS.put("func", FUNC);
        KEYWORDS.put("def", DEF);
        KEYWORDS.put("namespace", NAMESPACE);
        KEYWORDS.put("import", IMPORT);
        KEYWORDS.put("null", NULL);
        KEYWORDS.put("switch", SWITCH);
        KEYWORDS.put("case", CASE);
        KEYWORDS.put("default", DEFAULT);
        KEYWORDS.put("like", LIKE);
        KEYWORDS.put("final", FINAL);
        KEYWORDS.put("native", NATIVE);
        KEYWORDS.put("free", FREE);
        KEYWORDS.put("thread", THREAD);
        KEYWORDS.put("try", TRY);
        KEYWORDS.put("catch", CATCH);
        KEYWORDS.put("finally", FINALLY);
        KEYWORDS.put("throw", THROW);
    }

    public static TokenType fromKeyword(String text) {
        return KEYWORDS.get(text);
    }

    public static boolean isTypeKeyword(TokenType type) {
        return type == T_BYTE || type == T_INT || type == T_LONG ||
               type == T_FLOAT || type == T_DOUBLE || type == T_BOOL ||
               type == T_STRING || type == T_STR || type == VOID || type == VAR;
    }
}
