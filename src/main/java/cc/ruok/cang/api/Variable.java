package cc.ruok.cang.api;

/**
 * 属性 / 字段 / 参数 / 局部变量。
 * 类与实例字段、函数参数共用此类型（参数带默认值，字段带声明初值）。
 */
public class Variable extends Element {

    /** 类型文本，如 int、String[]、List&lt;String&gt;、var（推导位）。 */
    public String type;

    public boolean isStatic;
    public boolean isFinal;

    /**
     * 声明初值 / 参数默认值的文本（如 "0"、"\"anon\""、"null"），无则为 null。
     */
    public String defaultValue;

    /** 所属类的简单名；全局变量 / 局部变量 / 参数为 null。 */
    public String owner;

    public String getType() {
        return type;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public boolean isFinal() {
        return isFinal;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public String getOwner() {
        return owner;
    }

    /** 展示用：name : type [= default] */
    public String getDetail() {
        StringBuilder sb = new StringBuilder(name == null ? "?" : name);
        if (type != null) sb.append(": ").append(type);
        if (defaultValue != null) sb.append(" = ").append(defaultValue);
        return sb.toString();
    }
}
