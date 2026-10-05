# String 字符串类

> 返回 [文档索引](../README.md)

## 要点
- length / substring / indexOf / indexOfIgnoreCase / startsWith / endsWith
- toUpper / toLower / trim（均已在编译器内建实现，无需 import）
- parseInt / parseLong / parseDouble：完整数字校验，非法输入或超范围是运行时错误（首尾空白按 Java 惯例忽略）
- split / lastIndexOf 暂未提供（v1 边界）
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
"42".parseInt()       # 42
"3.25".parseDouble()  # 3.25
"1234567890123".parseLong()
```

字符串对象是不可变的，方法不会修改原字符串，而是返回新值或新字符串。

`string` 基本类型不能直接调用这些方法，需要先转换为 `String`：

```cang
string raw = 'hello'
String boxed = new String(raw)
```

---

