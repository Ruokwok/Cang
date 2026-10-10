package cc.ruok.cang.api;

/**
 * 代码补全的一条候选项。
 */
public class Completion extends Element {

    public enum Kind {
        KEYWORD, CLASS, METHOD, FIELD, VARIABLE, FUNCTION
    }

    public Kind kind;
    /** 展示详情：签名 / 类型（悬浮第二行）。 */
    public String detail;
    /** 实际插入文本（默认等于 name）。 */
    public String insertText;
    public boolean isStatic;

    public Kind getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }

    public String getInsertText() {
        return insertText != null ? insertText : name;
    }

    public boolean isStatic() {
        return isStatic;
    }

    @Override
    public String toString() {
        return kind + " " + name + (detail != null ? " (" + detail + ")" : "");
    }
}
