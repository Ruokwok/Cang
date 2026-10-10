package cc.ruok.cang.api;

import java.util.ArrayList;
import java.util.List;

/**
 * 类索引条目：字段与方法列表（不含继承——继承链由 {@link CangApi} 组合查询）。
 */
public class Class extends Element {

    /** 父类简单名；无显式父类时为 Object。 */
    public String superClass = "Object";
    /** 命名空间（如 cang/lang），无则为空串。 */
    public String namespace = "";
    public boolean isAbstract;

    public List<Function> functions = new ArrayList<>();
    public List<Variable> fields = new ArrayList<>();

    public String getSuperClass() {
        return superClass;
    }

    public String getNamespace() {
        return namespace;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    /** 全限定名：命名空间的 / 换成 _ 再接类名（a/util/Logger → a_util_Logger）。 */
    public String getQualifiedName() {
        if (namespace == null || namespace.isEmpty()) return name;
        return namespace.replace('/', '_') + "_" + name;
    }

    /** 本类声明的静态方法（不含继承）。 */
    public List<Function> getStaticFunctions() {
        List<Function> r = new ArrayList<>();
        for (Function f : functions) if (f.staticMethod) r.add(f);
        return r;
    }

    /** 本类声明的成员（实例）方法与构造方法（不含继承）。 */
    public List<Function> getMemberFunctions() {
        List<Function> r = new ArrayList<>();
        for (Function f : functions) if (f.memberMethod) r.add(f);
        return r;
    }

    /** 本类声明的实例字段（不含继承）。 */
    public List<Variable> getVariables() {
        List<Variable> r = new ArrayList<>();
        for (Variable v : fields) if (!v.isStatic) r.add(v);
        return r;
    }

    /** 本类声明的静态字段。 */
    public List<Variable> getStaticVariables() {
        List<Variable> r = new ArrayList<>();
        for (Variable v : fields) if (v.isStatic) r.add(v);
        return r;
    }

    /** 构造方法（init / _init 重载）。 */
    public List<Function> getConstructors() {
        List<Function> r = new ArrayList<>();
        for (Function f : functions) if (f.constructor) r.add(f);
        return r;
    }

    public Function findFunction(String fnName) {
        for (Function f : functions) if (fnName.equals(f.name)) return f;
        return null;
    }
}
