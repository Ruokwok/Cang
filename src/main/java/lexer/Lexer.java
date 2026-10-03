package lexer;

import java.util.ArrayList;
import java.util.List;

public class Lexer {

    private final String code;
    private int pos;
    private int line;
    private int column;

    public Lexer(String code) {
        this.code = code;
        this.pos = 0;
        this.line = 1;
        this.column = 1;
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (pos < code.length()) {
            skipWhitespaceAndComments();
            if (pos >= code.length()) break;

            Token token = nextToken();
            if (token != null) {
                tokens.add(token);
            }
        }
        tokens.add(new Token(TokenType.EOF, "", line, column));
        return tokens;
    }

    private void skipWhitespaceAndComments() {
        while (pos < code.length()) {
            char c = code.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r') {
                advance();
            } else if (c == '\n') {
                advance();
                line++;
                column = 1;
            } else if (c == '#' && pos + 1 < code.length() && code.charAt(pos + 1) == '*') {
                // Multi-line comment: #* ... *#  (unterminated -> immediate error, debug.md #26)
                int cmtLine = line, cmtCol = column;
                advance(); advance(); // skip #*
                boolean cmtClosed = false;
                while (pos < code.length()) {
                    if (code.charAt(pos) == '*' && pos + 1 < code.length() && code.charAt(pos + 1) == '#') {
                        advance(); advance(); // skip *#
                        cmtClosed = true;
                        break;
                    }
                    if (code.charAt(pos) == '\n') {
                        line++;
                        column = 1;
                    }
                    advance();
                }
                if (!cmtClosed) {
                    throw new RuntimeException("Lexer error at line " + cmtLine + ", column " + cmtCol
                        + ": Unterminated comment");
                }
            } else if (c == '#') {
                // Single-line comment
                while (pos < code.length() && code.charAt(pos) != '\n') {
                    advance();
                }
            } else {
                break;
            }
        }
    }

    private Token nextToken() {
        if (pos >= code.length()) return null;

        int startLine = line;
        int startCol = column;
        char c = code.charAt(pos);

        // Single quotes produce primitive str; double quotes produce String.
        if (c == '"' || c == '\'') return readQuotedString(c, startLine, startCol);

        // Multi-line string literal (backticks)
        if (c == '`') return readMultiLineString(startLine, startCol);

        // Number literal
        if (Character.isDigit(c)) return readNumber(startLine, startCol);

        // Identifier or keyword
        if (Character.isLetter(c) || c == '_') return readIdentOrKeyword(startLine, startCol);

        // Operators and delimiters
        return readOperatorOrDelimiter(startLine, startCol);
    }

    private Token readQuotedString(char quote, int startLine, int startCol) {
        advance(); // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < code.length() && code.charAt(pos) != quote) {
            char ch = code.charAt(pos);
            if (ch == '\\' && pos + 1 < code.length()) {
                advance();
                char esc = code.charAt(pos);
                switch (esc) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '\\': sb.append('\\'); break;
                    case '"': sb.append('"'); break;
                    case '\'': sb.append('\''); break;
                    case '0': sb.append('\0'); break;
                    default: sb.append(esc); break;
                }
                if (esc == '\n') {
                    // Escaped newline also advances the line counter (debug.md #26).
                    advance();
                    line++;
                    column = 1;
                    continue;
                }
                advance();
            } else {
                sb.append(ch);
                if (ch == '\n') {
                    // Count real newlines inside the literal so later positions stay correct.
                    advance();
                    line++;
                    column = 1;
                    continue;
                }
                advance();
            }
        }
        if (pos >= code.length()) {
            // Never saw the closing quote — fail at the OPENING position instead of
            // silently swallowing the rest of the file (debug.md #26).
            throw new RuntimeException("Lexer error at line " + startLine + ", column " + startCol
                + ": Unterminated string");
        }
        advance(); // skip closing quote
        TokenType type = quote == '\'' ? TokenType.STR_LIT : TokenType.STRING_LIT;
        return new Token(type, sb.toString(), startLine, startCol);
    }

    private Token readMultiLineString(int startLine, int startCol) {
        advance(); // skip opening `
        StringBuilder sb = new StringBuilder();
        while (pos < code.length() && code.charAt(pos) != '`') {
            if (code.charAt(pos) == '\\') {
                advance();
                if (pos >= code.length()) break;
                char esc = code.charAt(pos);
                switch (esc) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '\\': sb.append('\\'); break;
                    case '`': sb.append('`'); break;
                    default: sb.append(esc); break;
                }
            } else {
                if (code.charAt(pos) == '\n') {
                    sb.append('\n');
                    advance();
                    line++;
                    column = 1;
                    continue;
                }
                sb.append(code.charAt(pos));
            }
            advance();
        }
        if (pos >= code.length()) {
            throw new RuntimeException("Lexer error at line " + startLine + ", column " + startCol
                + ": Unterminated string");
        }
        advance(); // skip closing `
        return new Token(TokenType.STRING_LIT, sb.toString(), startLine, startCol);
    }

    private Token readNumber(int startLine, int startCol) {
        int start = pos;
        boolean isFloat = false;

        // Handle hex (0x) and binary (0b) prefixes
        if (code.charAt(pos) == '0' && pos + 1 < code.length()) {
            char next = Character.toLowerCase(code.charAt(pos + 1));
            if (next == 'x') {
                advance(); advance();
                while (pos < code.length() && (isHexDigit(code.charAt(pos)) || code.charAt(pos) == '_')) advance();
                return new Token(TokenType.INT_LIT, stripUnderscores(code.substring(start, pos)), startLine, startCol);
            } else if (next == 'b') {
                advance(); advance();
                while (pos < code.length() && (code.charAt(pos) == '0' || code.charAt(pos) == '1' || code.charAt(pos) == '_')) advance();
                return new Token(TokenType.INT_LIT, stripUnderscores(code.substring(start, pos)), startLine, startCol);
            }
        }

        // Digits with underscore separators
        while (pos < code.length() && (Character.isDigit(code.charAt(pos)) || code.charAt(pos) == '_')) advance();

        // Decimal point
        if (pos < code.length() && code.charAt(pos) == '.' &&
            pos + 1 < code.length() && (Character.isDigit(code.charAt(pos + 1)) || code.charAt(pos + 1) == '_')) {
            isFloat = true;
            advance(); // skip .
            while (pos < code.length() && (Character.isDigit(code.charAt(pos)) || code.charAt(pos) == '_')) advance();
        }

        String raw = code.substring(start, pos);
        String text = stripUnderscores(raw);

        // Type suffix
        if (pos < code.length()) {
            char suffix = code.charAt(pos);
            switch (suffix) {
                case 'i': case 'I':
                    advance();
                    return new Token(TokenType.INT_LIT, text, startLine, startCol);
                case 'l': case 'L':
                    advance();
                    return new Token(TokenType.LONG_LIT, text, startLine, startCol);
                case 'f': case 'F':
                    advance();
                    return new Token(TokenType.FLOAT_LIT, text, startLine, startCol);
                case 'd': case 'D':
                    advance();
                    return new Token(TokenType.DOUBLE_LIT, text, startLine, startCol);
                case 'b': case 'B':
                    advance();
                    return new Token(TokenType.INT_LIT, text, startLine, startCol); // byte stored as int
            }
        }

        if (isFloat) {
            return new Token(TokenType.DOUBLE_LIT, text, startLine, startCol);
        }
        return new Token(TokenType.INT_LIT, text, startLine, startCol);
    }

    private String stripUnderscores(String s) {
        return s.replace("_", "");
    }

    private Token readIdentOrKeyword(int startLine, int startCol) {
        int start = pos;
        while (pos < code.length() && (Character.isLetterOrDigit(code.charAt(pos)) || code.charAt(pos) == '_')) {
            advance();
        }
        String text = code.substring(start, pos);
        TokenType type = TokenType.fromKeyword(text);
        if (type != null) {
            return new Token(type, text, startLine, startCol);
        }
        return new Token(TokenType.IDENT, text, startLine, startCol);
    }

    private Token readOperatorOrDelimiter(int startLine, int startCol) {
        char c = code.charAt(pos);
        char peek = pos + 1 < code.length() ? code.charAt(pos + 1) : '\0';

        switch (c) {
            case '+':
                advance();
                if (peek == '+') { advance(); return new Token(TokenType.INC, "++", startLine, startCol); }
                if (peek == '=') { advance(); return new Token(TokenType.PLUS_ASSIGN, "+=", startLine, startCol); }
                return new Token(TokenType.PLUS, "+", startLine, startCol);
            case '-':
                advance();
                if (peek == '-') { advance(); return new Token(TokenType.DEC, "--", startLine, startCol); }
                if (peek == '=') { advance(); return new Token(TokenType.MINUS_ASSIGN, "-=", startLine, startCol); }
                if (peek == '>') { advance(); return new Token(TokenType.ARROW, "->", startLine, startCol); }
                return new Token(TokenType.MINUS, "-", startLine, startCol);
            case '*':
                advance();
                if (peek == '=') { advance(); return new Token(TokenType.STAR_ASSIGN, "*=", startLine, startCol); }
                return new Token(TokenType.STAR, "*", startLine, startCol);
            case '/':
                advance();
                if (peek == '/') { advance(); return new Token(TokenType.DOUBLE_SLASH, "//", startLine, startCol); }
                if (peek == '=') { advance(); return new Token(TokenType.SLASH_ASSIGN, "/=", startLine, startCol); }
                return new Token(TokenType.SLASH, "/", startLine, startCol);
            case '%': advance(); return new Token(TokenType.PERCENT, "%", startLine, startCol);
            case '=':
                advance();
                if (peek == '=') { advance(); return new Token(TokenType.EQ, "==", startLine, startCol); }
                return new Token(TokenType.ASSIGN, "=", startLine, startCol);
            case '!':
                advance();
                if (peek == '=') { advance(); return new Token(TokenType.NEQ, "!=", startLine, startCol); }
                return new Token(TokenType.NOT, "!", startLine, startCol);
            case '<':
                advance();
                if (peek == '=') { advance(); return new Token(TokenType.LE, "<=", startLine, startCol); }
                return new Token(TokenType.LT, "<", startLine, startCol);
            case '>':
                advance();
                if (peek == '=') { advance(); return new Token(TokenType.GE, ">=", startLine, startCol); }
                return new Token(TokenType.GT, ">", startLine, startCol);
            case '&':
                advance();
                if (peek == '&') { advance(); return new Token(TokenType.AND, "&&", startLine, startCol); }
                errorAt(startLine, startCol, "Unexpected character '&'");
            case '|':
                advance();
                if (peek == '|') { advance(); return new Token(TokenType.OR, "||", startLine, startCol); }
                errorAt(startLine, startCol, "Unexpected character '|'");
            case '(': advance(); return new Token(TokenType.LPAREN, "(", startLine, startCol);
            case ')': advance(); return new Token(TokenType.RPAREN, ")", startLine, startCol);
            case '{': advance(); return new Token(TokenType.LBRACE, "{", startLine, startCol);
            case '}': advance(); return new Token(TokenType.RBRACE, "}", startLine, startCol);
            case ';': advance(); return new Token(TokenType.SEMICOLON, ";", startLine, startCol);
            case ',': advance(); return new Token(TokenType.COMMA, ",", startLine, startCol);
            case '.': advance(); return new Token(TokenType.DOT, ".", startLine, startCol);
            case '?': advance(); return new Token(TokenType.QUESTION, "?", startLine, startCol);
            case ':':
                advance();
                if (peek == ':') { advance(); return new Token(TokenType.DOUBLE_COLON, "::", startLine, startCol); }
                return new Token(TokenType.COLON, ":", startLine, startCol);
            case '[': advance(); return new Token(TokenType.LBRACKET, "[", startLine, startCol);
            case ']': advance(); return new Token(TokenType.RBRACKET, "]", startLine, startCol);
            default:
                error("Unexpected character '" + c + "'");
                advance();
                return null;
        }
    }

    private void advance() {
        pos++;
        column++;
    }

    private boolean isHexDigit(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private void error(String msg) {
        throw new RuntimeException("Lexer error at line " + line + ", column " + column + ": " + msg);
    }

    /** Error at an explicit (already-known) position — for cases where advance() moved the cursor. */
    private void errorAt(int l, int c, String msg) {
        throw new RuntimeException("Lexer error at line " + l + ", column " + c + ": " + msg);
    }
}
