package cc.ruok.cang.util;

/**
 * Browser/JS-style compile error with source location.
 * Format:
 *   error: message
 *     --> file.cang:line:col
 *      |
 *   9 | source line here
 *      |     ^^^^^
 */
public class CompileError extends RuntimeException {
    public final String file;
    public final int line;
    public final int column;
    public final String sourceLine;
    public final int length; // caret length (default 1)

    public CompileError(String message, String file, int line, int column, String sourceLine, int length) {
        super(message);
        this.file = file;
        this.line = line;
        this.column = column;
        this.sourceLine = sourceLine;
        this.length = Math.max(length, 1);
    }

    public CompileError(String message, String file, int line, int column, String sourceLine) {
        this(message, file, line, column, sourceLine, 1);
    }

    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("\u001B[31merror\u001B[0m: ").append(getMessage()).append("\n");
        sb.append("  --> ").append(file).append(":").append(line).append(":").append(column).append("\n");
        sb.append("   |\n");

        // Show source line
        String lineNum = String.valueOf(line);
        String padding = " ".repeat(lineNum.length());
        sb.append(" ").append(lineNum).append(" | ").append(sourceLine).append("\n");

        // Caret
        sb.append(" ").append(padding).append(" | ");
        sb.append(" ".repeat(Math.max(column - 1, 0)));
        sb.append("\u001B[31m").append("^".repeat(length)).append("\u001B[0m").append("\n");
        sb.append("   |");

        return sb.toString();
    }
}
