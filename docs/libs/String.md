# String 字符串类

> 返回 [文档索引](../README.md)

## 要点
- length / substring / indexOf / indexOfIgnoreCase / startsWith / endsWith
- charAt（单字节字符串）与 byteAt（0..255 字节值，O(1)、不分配）
- toUpper / toLower / trim（均已在编译器内建实现，无需 import）
- parseInt / parseLong / parseDouble：完整数字校验，非法输入或超范围是运行时错误（首尾空白按 Java 惯例忽略）
- split / lastIndexOf 暂未提供（v1 边界）
- 方法链式调用与 `String!$(...)` 数值转换


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

### byteAt：线性扫描用的字节访问

`byteAt(i)` 返回 `i` 处的原始字节值（`0..255`），UTF-8 字节粒度；不调用 `strlen`、不分配内存：

```cang
String s = "中A"
int n = s.length()      # 4（UTF-8 字节数）
s.byteAt(0)             # 228 (0xE4)
s.byteAt(3)             # 65  ('A')
```

- 上界**不检查**（检查需要 `strlen`，会让扫描退化为 O(n²)），调用方必须保证 `0 <= i < n`；
- 负下标抛运行时错误；
- 与 `charAt` 的取舍：`charAt` 返回单字节 `String`，但每次调用都会 `strlen` + 分配，逐字符循环是 O(n²)；长文本扫描（JSON 解析、Lexer）应缓存 `length()` 并用 `byteAt`。

---
