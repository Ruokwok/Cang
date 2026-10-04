# Stdout 标准输出

> 返回 [文档索引](../README.md)

## 要点
- print 不换行 / println 换行
- 支持 int/long/float/double/bool/String/byte/null 七类实参；bool 打印 `true` / `false`（Java 风格，非 1/0）
- 编译器内建，无需 import（显式 import 亦可）


标准输出由编译器内建处理。

### 14.1 print

不换行：

```cang
Stdout.print("hello")
Stdout.print(123)
Stdout.print(1.5)

bool 也按 `true`/`false` 打印：

```cang
Stdout.println(true)    # true
Stdout.println(1 < 2)   # true
```
```

### 14.2 println

换行：

```cang
Stdout.println("hello")
Stdout.println(123)
Stdout.println(true)
```

支持的常用参数类型：

- `byte`
- `int`
- `long`
- `float`
- `double`
- `bool`
- `String`
- `str`

