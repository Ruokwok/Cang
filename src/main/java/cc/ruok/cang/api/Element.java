package cc.ruok.cang.api;

/**
 * 所有语言元素的基类（类 / 函数 / 字段 / 变量）。
 */
public class Element {

    public String name;

    /** 声明所在行（1 起），用于“转到定义”。 */
    public int line;

    public String getName() {
        return name;
    }

    public int getLine() {
        return line;
    }
}
