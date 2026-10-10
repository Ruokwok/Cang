package cc.ruok.cang.api;

/**
 * 一条语法诊断（编辑器波浪线 / 问题列表）。
 */
public class Diagnostic {

    public String message;
    public int line;
    public int column;

    public Diagnostic(String message, int line, int column) {
        this.message = message;
        this.line = line;
        this.column = column;
    }

    public String getMessage() {
        return message;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    @Override
    public String toString() {
        return line + ":" + column + " " + message;
    }
}
