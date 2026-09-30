package lexer;

public class Token {

    public TokenType type;
    public String value;
    public int line;
    public int column;

    public Token(TokenType type, String value, int line, int column) {
        this.type = type;
        this.value = value;
        this.line = line;
        this.column = column;
    }

    @Override
    public String toString() {
        return type + "(" + value + ")";
    }
}
