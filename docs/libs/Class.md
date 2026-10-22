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

## 限制

- 不支持数组类字面量（`String[].class`）；
- 不提供 Java 反射式 API（字段/方法枚举等），`Class` 只保存类名与父类信息；
- 不提供 `Object.getClass()`；当前入口是 `类名.class`；
- 泛型类没有独立的运行时类对象，类字面量面向具体类名；
- `Class` 的字段是私有的，元数据只读（请使用 getter），也不可 `new Class()`。
