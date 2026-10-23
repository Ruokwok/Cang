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
| `newInstance()` | `Object` | 反射构造：调用公开零参构造；失败抛可捕获运行时错误 |
| `newInstance(Object arg)` | `Object` | 反射构造：调用单参构造；基本类型参数需先用 `Field.getObject` 装箱 |
| `newInstance(Object[] args)` | `Object` | 反射构造：按 `args.length` 调用 N 参构造；空数组等价于零参构造 |

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

`newInstance` 有三个重载，均返回 `Object`，返回值可用转型取回具体类型：

```cang
newInstance()                 # 调用零参构造
newInstance(Object arg)       # 调用单参构造
newInstance(Object[] args)    # 按数组长度调用 N 参构造
```

### 零参与单参

```cang
# Point.cang
class Point
    int x = 7
    String tag = "pt"
```

```cang
# User.cang
class User
    String name = ""

    init(String name) {
        this.name = name
    }
```

```cang
import Point
import User

class Main
    static func void main(String[] args) {
        Object obj = Point.class.newInstance()
        var f = new Field(obj)
        Stdout.println(f.getInt("x"))        # 7
        Stdout.println(f.getString("tag"))   # pt

        Object user = User.class.newInstance("Ada")
        var uf = new Field(user)
        Stdout.println(uf.getString("name")) # Ada

        # 配合转型使用（需类型匹配）
        Point p = Point!$(obj)
        Stdout.println(p.x)                  # 7
    }
}
```

### 多参：Object[] 的长度就是构造函数参数个数

```cang
# Pair.cang
class Pair
    String left = ""
    String right = ""

    init(String left, String right) {
        this.left = left
        this.right = right
    }
```

```cang
import Pair

class Main
    static func void main(String[] args) {
        Object left = "L"
        Object right = "R"
        Object[] args = [left, right]
        Object pair = Pair.class.newInstance(args)

        var f = new Field(pair)
        Stdout.println(f.getString("left"))   # L
        Stdout.println(f.getString("right"))  # R
    }
}
```

- `Object[]` 的长度为 0 时调用零参构造；
- 参数个数必须**精确匹配**，不会使用构造函数默认参数值；
- `args` 本身为 `null` → 运行时错误；
- 数组元素为 `null` 时，引用类型参数接受 `null`；基本类型参数匹配失败；
- 也支持直接写数组字面量：`Pair.class.newInstance([left, right])`；
- 只有 `Object[]` 会按多参列表解释；`String[]`、`int[]` 等其他数组类型作为**单个 Object 实参**传给单参构造（例如 `init(String[] names)`）。数组字面量按多参列表处理。

### 基本类型参数：需要 Boxed

Cang 的 `Object` 不能接收基本类型，`newInstance(1)` 不是合法调用。基本类型必须先装箱；目前公开的装箱入口是 [Field.getObject](Field.md)：

```cang
# Source.cang
class Source
    int value = 42

# Counter.cang
class Counter
    int value = 0

    init(int value) {
        this.value = value
    }
```

```cang
import Source
import Counter

class Main
    static func void main(String[] args) {
        Object source = new Source()
        Object boxed = new Field(source).getObject("value")  # Boxed
        Object counter = Counter.class.newInstance(boxed)

        var f = new Field(counter)
        Stdout.println(f.getInt("value")) # 42
    }
}
```

多参基本类型同理：把每个 Boxed 放进 `Object[]`，数组长度与构造参数个数一致。Boxed 只保存原始位，不记录来源类型；`newInstance` 按目标构造参数的类型解释这些位，因此调用方应保证传入的 Boxed 与参数类型一致。

### 选择构造函数与失败行为

- 只考虑公开构造函数；`abstract` 类、私有类构造、私有 `_init()`、`native` 构造函数不参与；
- 支持从 `Object` 解码的参数类型：基本类型（Boxed）、`String`、`Object`、数组、类引用；其他参数类型（如函数值）的构造函数会被跳过；
- 先按参数个数筛选；参数个数相同的多个构造函数按“参数类型具体程度”排序，基本类型需传入 Boxed，类参数按运行时 `typeId` 检查是否为该类或其子类；
- `String` 和数组没有运行时类型标签，若同一参数个数存在仅靠它们区分的重载，匹配是尽力而为的；无法确定时建议只保留一个同参数个数的构造函数；
- 只有带参构造的类调用 `newInstance()`、参数个数不匹配、基本类型参数不是 Boxed、类参数类型不匹配 → 运行时错误；
- `String.class` / `int.class` 等特殊类字面量 → 运行时错误；
- 失败抛出可被 `try/catch` 捕获的 `Error`：
  - 零参调用：`Class.newInstance: Point cannot be instantiated`
  - 带参调用：`Class.newInstance: Point has no matching constructor for the given arguments`
  - 参数数组为 `null`：`Class.newInstance: arguments array is null`
- 构造函数体中抛出的异常正常传播。

与 [Field.list()](Field.md) 组合可实现“反射构造 + 字段枚举”的序列化雏形。

## 限制

- 不支持数组类字面量（`String[].class`）；
- 不支持 Java 风格可变参数；多参构造使用单个 `Object[]` 传入；
- `Object[]` 的元素必须是 `Object`：`Object[] args = ["a"]` 编译失败，需要先 `Object a = "a"`；基本类型需使用 Boxed；
- 目前没有公开的“装箱”API，基本类型只能通过 [Field.getObject](Field.md) 等已有 Boxed 来源获得；
- `String`/数组没有运行时类型标签，重载构造函数的选择存在尽力而为的边界（见上文）；
- `Class` 本身不提供字段/方法枚举；按字段名读取实例字段请用 [Field](Field.md)，方法枚举仍不支持；
- 不提供 `Object.getClass()`；当前入口是 `类名.class`；
- 泛型类没有独立的运行时类对象，类字面量面向具体类名；
- `Class` 的字段是私有的，元数据只读（请使用 getter），也不可 `new Class()`。
