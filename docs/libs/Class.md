# Class 类信息

> 返回 [文档索引](../README.md)

## 要点

- `cang/lang/Class`：运行时类信息对象，对应 Java 的 `java.lang.Class`，**不含泛型**
- 通过类字面量获取：`Apple.class`、`String.class`、`int.class`
- 同一个类全局只有**一个** `Class` 实例，可直接用 `==` 比较身份
- 自动查找 `cang/lang/Class`，无需显式 `import`；也可 `import cang/lang/Class`（支持 `as` 别名）
- `Class` 对象由编译器在 `main` 启动时统一创建，`Class` 本身不能 `new`

## 基本用法

```cang
# Apple.cang
class Apple
    int size = 1
```

```cang
# Main.cang
import Apple

class Main
    static func void main(String[] args) {
        Class a = Apple.class
        Class b = Apple.class

        Stdout.println(a.getName())          # Apple
        Stdout.println(a.getSimpleName())    # Apple
        Stdout.println(a == b)               # true —— 全局单例
        Stdout.println(a.toString())         # class Apple
    }
```

带命名空间的类，`getName()` 返回与 `Object.getClassName()` 一致的全名（命名空间用 `_` 连接）：

```cang
namespace demo

class Apple
    int size = 1
```

```cang
import Apple

Stdout.println(Apple.class.getName())        # demo_Apple
Stdout.println(Apple.class.getSimpleName())  # Apple
```

## 继承关系

```cang
# Fruit.cang
class Fruit
    String kind = "fruit"
```

```cang
# Apple.cang
import Fruit

class Apple : Fruit
    int size = 1
```

```cang
import Apple

class Main
    static func void main(String[] args) {
        Class apple = Apple.class
        Class fruit = apple.getSuperclass()

        Stdout.println(fruit.getName())                    # Fruit
        Stdout.println(fruit.isAssignableFrom(apple))      # true
        Stdout.println(apple.isAssignableFrom(fruit))      # false
        Stdout.println(apple.isAssignableFrom(apple))      # true

        Class root = fruit.getSuperclass()
        Stdout.println(root.getName())                     # cang_lang_Object
        Stdout.println(root.getSuperclass() == null)       # true —— Object 没有父类
    }
```

## API

| 方法 | 返回 | 说明 |
|------|------|------|
| `getName()` | `String` | 全名，如 `demo_Apple`、`cang_lang_Object` |
| `getSimpleName()` | `String` | 简单名，如 `Apple` |
| `getSuperclass()` | `Class` | 父类；`Object`、基本类型、`void` 返回 `null` |
| `isAssignableFrom(Class other)` | `bool` | 本类是否等于 `other` 或是其父类/祖先 |
| `toString()` | `String` | `"class " + 全名` |
| `newInstance()` | `Object` | 反射构造：按本类公开零参构造创建实例；失败抛可捕获运行时错误 |

## String 与基本类型

类字面量也接受 `String`、基本类型与 `void`：

```cang
Stdout.println(String.class.getName())   # String
Stdout.println(int.class.getName())      # int
Stdout.println(bool.class.getName())     # bool
Stdout.println(void.class.getName())     # void

Stdout.println(String.class.getSuperclass().getName())  # cang_lang_Object
Stdout.println(int.class.getSuperclass() == null)       # true
```

这些 `Class` 对象同样全局单例；`String` 被视为普通引用类，父类为 `Object`。

## 反射构造（newInstance）

`newInstance()` 按本类的**公开零参构造**创建实例并返回 `Object`。返回值用转型取回具体类型：

```cang
# Point.cang
class Point
    int x = 7
    String tag = "pt"
```

```cang
# Main.cang
import Point

class Main
    static func void main(String[] args) {
        Object obj = Point.class.newInstance()
        var f = new Field(obj)
        Stdout.println(f.getInt("x"))    # 7
        Stdout.println(f.getString("tag")) # pt

        # 配合转型使用（需类型匹配）
        Point p = Point!$(obj)
        Stdout.println(p.x)              # 7
    }
}
```

要求与失败行为：

- 类必须非 `abstract`、非私有构造（`class _Foo` / 私有 `_init()`）；
- 必须存在公开零参构造：显式 `init()`，或无显式 `init` 时的隐式零参构造（字段只有默认值的常见形态）；
- 只有带参构造（如仅 `init(int x)`）→ 运行时错误；
- `String.class` / `int.class` 等特殊类字面量 → 运行时错误；
- 失败抛出可被 `try/catch` 捕获的 `Error`，消息形如
  `Class.newInstance: Point cannot be instantiated`；
- 构造函数体中抛出的异常正常传播。

与 [Field.list()](Field.md) 组合可实现"反射构造 + 字段枚举"的序列化雏形。

## 限制

- 不支持数组类字面量（`String[].class`）；
- `Class` 本身不提供字段/方法枚举；按字段名读取实例字段请用 [Field](Field.md)，方法枚举仍不支持；
- 不提供 `Object.getClass()`；当前入口是 `类名.class`；
- 泛型类没有独立的运行时类对象，类字面量面向具体类名；
- `Class` 的字段是私有的，元数据只读（请使用 getter），也不可 `new Class()`。
