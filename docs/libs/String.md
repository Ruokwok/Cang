# String 字符串类

> 返回 [文档索引](../README.md)

## 要点
- length / substring / indexOf / startsWith / endsWith
- toUpper / toLower / trim / 拆分与查找
- 方法链式调用与 str 的显式转换


`String` 是不可变引用类型，标准库声明位于 `stdlib/cang/lang/String.cang`。

常用方法：

```cang
String s = " Hello Cang "

s.length()
s.equals("Hello Cang")
s.startsWith(" Hello")
s.endsWith("Cang ")
s.indexOf("Cang")
s.substring(1, 6)
s.toUpper()
s.toLower()
s.trim()
s.indexOfIgnoreCase("cang")
```

字符串对象是不可变的，方法不会修改原字符串，而是返回新值或新字符串。

`string` 基本类型不能直接调用这些方法，需要先转换为 `String`：

```cang
string raw = 'hello'
String boxed = new String(raw)
```

---

