package cc.ruok.cang.api;

import java.util.ArrayList;
import java.util.List;

/**
 * 方法 / 全局函数 / 构造方法（init / _init）。
 */
public class Function extends Element {

    public String returnType;
    /** 参数列表（含默认值）。 */
    public List<Variable> params = new ArrayList<>();
    /** 所属类简单名；顶层全局函数为 null。 */
    public String owner;

    public boolean staticMethod;
    public boolean memberMethod;
    public boolean finalMethod;
    public boolean nativeMethod;
    public boolean abstractMethod;
    public boolean constructor;

    public String getReturnType() {
        return returnType;
    }

    public List<Variable> getParams() {
        return params;
    }

    public String getOwner() {
        return owner;
    }

    /** 是否为静态方法 */
    public boolean isStatic() {
        return staticMethod;
    }

    /** 是否为成员（实例）方法 */
    public boolean isMember() {
        return memberMethod;
    }

    /** 是否不可重写 */
    public boolean isFinal() {
        return finalMethod;
    }

    /** 是否 native（无 Cang 方法体，由编译器/运行时实现） */
    public boolean isNative() {
        return nativeMethod;
    }

    /** 是否抽象方法（须由子类实现） */
    public boolean isAbstract() {
        return abstractMethod;
    }

    /** 是否构造方法（init / _init） */
    public boolean isConstructor() {
        return constructor;
    }

    /**
     * 提示 / 悬浮用签名，如：
     * {@code int add(int a, int b = 1)}、{@code void main(String[] args)}。
     */
    public String getSignature() {
        StringBuilder sb = new StringBuilder();
        if (returnType != null && !returnType.isEmpty()) sb.append(returnType).append(' ');
        sb.append(name == null ? "?" : name).append('(');
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            Variable p = params.get(i);
            sb.append(p.type == null ? "?" : p.type);
            if (p.name != null && !p.name.isEmpty()) sb.append(' ').append(p.name);
            if (p.defaultValue != null) sb.append(" = ").append(p.defaultValue);
        }
        sb.append(')');
        return sb.toString();
    }

    @Override
    public String toString() {
        return getSignature();
    }
}
