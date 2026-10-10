package cc.ruok.cang.api;

import java.util.List;

/**
 * 调用处的签名帮助（参数提示）：候选重载 + 当前激活项。
 */
public class SignatureHelp {

    /** 与调用匹配的方法重载（可能为空 = 找不到该方法）。 */
    public List<Function> overloads;

    /** 当前激活的重载下标（-1 = 尚未选定 / 无候选）。 */
    public int activeIndex = -1;

    /** 光标所在的参数下标（0 起；不在参数区为 -1）。 */
    public int activeParameter = -1;

    public List<Function> getOverloads() {
        return overloads;
    }

    public int getActiveIndex() {
        return activeIndex;
    }

    public int getActiveParameter() {
        return activeParameter;
    }

    public Function getActive() {
        if (activeIndex >= 0 && overloads != null && activeIndex < overloads.size()) {
            return overloads.get(activeIndex);
        }
        return null;
    }
}
