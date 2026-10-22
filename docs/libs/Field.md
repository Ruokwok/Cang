# Field 字段反射

> 返回 [文档索引](../README.md)

## 要点

- `cang/lang/Field`：按字段名读取对象字段的反射访问器
- `new Field(obj)` 绑定目标对象，之后用 `getInt` / `getString` / `getObject` 等读取
- 字段不存在、目标为 `null`、字段类型不匹配都是可捕获的运行时错误
- 引用 `Field` 时自动加载标准库，无需显式 `import`
- 只读：当前没有按名写字段的 API

## 用法

```cang
# Point.cang
class Point
    int x = 7
    String name = "origin"
```

```cang
# Main.cang
import Point

class Main
    static func void main(String[] args) {
        var p = new Point()
        var f = new Field(p)

        Stdout.println(f.getInt("x"))       # 7
        Stdout.println(f.getString("name")) # origin
        Stdout.println(f.getType("x"))      # int
    }
```

## API

| 方法 | 返回 | 说明 |
|---|---|---|
| `Field(Object obj)` | `Field` | 绑定目标对象 |
| `getObject(String name)` | `Object` | 读引用字段；基本类型字段返回内部装箱对象（不可 `new`） |
| `getString(String name)` | `String` | 读 `String` 字段 |
| `getInt(String name)` | `int` | 读 `int` 字段 |
| `getLong(String name)` | `long` | 读 `long` 字段 |
| `getByte(String name)` | `byte` | 读 `byte` 字段 |
| `getBool(String name)` | `bool` | 读 `bool` 字段 |
| `getFloat(String name)` | `float` | 读 `float` 字段 |
| `getDouble(String name)` | `double` | 读 `double` 字段 |
| `getType(String name)` | `String` | 字段类型名：基本类型原名；`String` 为 `String`；数组为 `Array`；引用类型为全限定名 |

## 类型与错误

- `getInt` 等基本类型访问器要求字段的真实类型与访问器一致，否则抛
  `Field.getInt: field "x" is not int` 一类错误；
- 字段不存在：`Field.getInt: target object has no field "x"`；
- 目标对象为 `null`：`Field.getInt: target object is null`；
- 以上错误都可被 `try/catch` 捕获，未捕获时按普通运行时错误打印并退出 1；
- `getObject` 读基本类型字段时返回内部装箱对象（按位保存，不可 `new`），用对应的
  `getInt` / `getLong` / ... 读取原始值更直接。

## 与 Class 的区别

`Class`（`Apple.class`）只提供类名、父类和类型判断；`Field` 负责按字段名读取实例字段。
两者都不提供方法枚举或完整反射 API。

---
